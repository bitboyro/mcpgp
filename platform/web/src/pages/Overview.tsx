import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type Change, type Diagnostic, type Manifest, type Tool } from '../api'
import { PACKS, choices, diffLines, packChange, packOn } from '../choices'
import { AUTH_LABELS, EXPOSURE, counts, listedTools, useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, Segmented, ago } from '../ui'

type Tab = 'agent' | 'customer' | 'package' | 'checks' | 'changes'

export function BuildButton({ id }: { id: string }) {
  const { can } = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const start = useMutation({
    mutationFn: () => api.startBuild(id),
    onSuccess: async (build) => {
      await queryClient.invalidateQueries({ queryKey: ['builds', id] })
      navigate(`/servers/${id}/builds?build=${build.id}`)
    },
  })
  if (!can('builds:run')) return null
  return (
    <>
      {start.error && <ErrorBox error={start.error} />}
      <button type="button" className="btn btn-primary" onClick={() => start.mutate()} disabled={start.isPending}>Build</button>
    </>
  )
}

export function OverviewPage() {
  const { id = '' } = useParams()
  const { can } = useSession()
  const queryClient = useQueryClient()
  const server = useServer(id)
  const manifest = useManifest(id, 'latest', server.data?.latestVersion != null)
  const [tab, setTab] = useState<Tab>('agent')
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [saving, setSaving] = useState(false)
  const [message, setMessage] = useState('')
  const latest = server.data?.versions.at(-1)
  const compileError = manifest.error instanceof ApiError ? manifest.error : null
  const changes = Object.values(pending).map((p) => p.change)
  const base = server.data?.latestVersion ?? 0
  const preview = useQuery({
    queryKey: ['edit-preview', id, base, changes],
    queryFn: () => api.previewEdit(id, base, changes),
    enabled: changes.length > 0 && base > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const shown = changes.length > 0 ? preview.data?.manifest ?? manifest.data : manifest.data
  const broken = changes.length > 0 && preview.data && !preview.data.manifest
  const save = useMutation({
    mutationFn: () => api.saveEdit(id, base, changes, message || describeChanges(Object.values(pending))),
    onSuccess: async () => {
      setPending({})
      setSaving(false)
      setTab('agent')
      setMessage('')
      for (const key of ['server', 'manifest', 'servers', 'example-config', 'version']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const editable = can('servers:write') && !!manifest.data && !server.data?.archived

  const pick = (key: string, label: string, original: boolean, change: Change) => {
    setPending((now) => {
      const next = { ...now }
      if (original) delete next[key]
      else next[key] = { label, change }
      return next
    })
    save.reset()
  }

  return (
    <>
      <PageHead title={manifest.data?.server.title ?? server.data?.title ?? id}
        sub="Change something on the left. The right side shows the server you'll ship.">
        {changes.length > 0 ? (
          <>
            <span className="hint">{changes.length} unsaved change{changes.length === 1 ? '' : 's'}</span>
            <button type="button" className="btn" onClick={() => { setPending({}); setSaving(false); setTab('agent'); save.reset() }}>Discard</button>
            <button type="button" className="btn" onClick={() => setTab('changes')}>Review</button>
            <button type="button" className="btn btn-primary" disabled={!!broken || preview.isFetching} onClick={() => setSaving(true)}>Save as version {base + 1}</button>
          </>
        ) : (
          <>
            <Link to={`/servers/${id}/sources`} className="btn">Upload version</Link>
            <BuildButton id={id} />
          </>
        )}
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={server.error} />
          {server.isLoading && <Loading />}
          {saving && (
            <form className="card" style={{ borderColor: 'var(--accent)' }} onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
              <h2>Save as version {base + 1}</h2>
              <ul className="hint" style={{ margin: 0, paddingLeft: 18 }}>{Object.values(pending).map((p) => <li key={p.change.path}>{p.label}</li>)}</ul>
              <label className="field">What changed (optional)
                <input className="input" value={message} placeholder={describeChanges(Object.values(pending))} onChange={(e) => setMessage(e.target.value)} maxLength={200} />
              </label>
              <ErrorBox error={save.error} />
              {save.error instanceof ApiError && save.error.status === 409 && (
                <p className="hint">Someone saved a newer version. <button type="button" className="btn btn-sm" onClick={() => { setPending({}); setSaving(false); save.reset(); void queryClient.invalidateQueries({ queryKey: ['server', id] }) }}>Reload</button></p>
              )}
              <div className="actions">
                <button type="submit" className="btn btn-primary" disabled={save.isPending}>Save</button>
                <button type="button" className="btn" onClick={() => setSaving(false)}>Cancel</button>
              </div>
            </form>
          )}
          {compileError && (
            <div className="card">
              <h2>This version doesn't compile</h2>
              <p className="hint">{compileError.message}. The checks on <Link to={`/servers/${id}/sources`}>Sources</Link> say why.</p>
            </div>
          )}
          {manifest.data && shown && (
            <Choices id={id} original={manifest.data} shown={shown} editable={editable} pending={pending} pick={pick}
              variant={!!(server.data?.parent || manifest.data.build.extends)} />
          )}
          {server.data && (
            <div className="card">
              <h2>Definition</h2>
              <dl className="kv">
                <dt>Server</dt><dd className="mono">{manifest.data?.server.name ?? id} {manifest.data?.server.version}</dd>
                <dt>Version</dt><dd>{latest ? <>{latest.number} · by {latest.author} {ago(latest.created)}{latest.message ? ` · ${latest.message}` : ''}</> : 'none yet'}</dd>
                {server.data.parent && <><dt>Variant of</dt><dd><Link to={`/servers/${server.data.parent}`}>{server.data.parent}</Link></dd></>}
                {manifest.data?.build.extends && <><dt>Extends</dt><dd className="mono">{manifest.data.build.extends.name}{manifest.data.build.extends.version ? '@' + manifest.data.build.extends.version : ''}</dd></>}
              </dl>
              <p className="hint">Each saved change becomes a new version, like an upload, with your name in Activity. Comments and layout in the file stay as they are.</p>
            </div>
          )}
        </section>
        <section className="stage" aria-label="Preview">
          <div className="card-head">
            <Segmented role="tab" label="Preview" value={tab} onChange={setTab}
              options={[['agent', 'Agent'], ['customer', 'Customer setup'], ['package', 'Package'], ['checks', 'Checks'], ...(changes.length ? [['changes', 'Changes'] as [Tab, string]] : [])]} />
            <span className="live">{changes.length ? (preview.isFetching ? 'Updating…' : 'Unsaved preview') : 'Live'}</span>
          </div>
          {preview.error && <ErrorBox error={preview.error} />}
          {broken && <div className="note note-bad">With these changes the definition doesn't compile; see Checks.</div>}
          {manifest.isLoading && <Loading what="the preview" />}
          {shown && tab === 'agent' && <AgentPreview manifest={shown} />}
          {shown && tab === 'customer' && <CustomerPreview id={id} text={changes.length ? preview.data?.exampleConfig : undefined} />}
          {shown && tab === 'package' && <PackagePreview id={id} manifest={shown} />}
          {tab === 'checks' && <Checks diagnostics={(changes.length ? preview.data?.manifest?.diagnostics ?? preview.data?.diagnostics : undefined)
            ?? manifest.data?.diagnostics ?? compileDiagnostics(compileError)} />}
          {tab === 'changes' && changes.length > 0 && <DefinitionDiff id={id} version={base} after={preview.data?.definition} />}
          {tab === 'changes' && changes.length === 0 && <p className="empty">No unsaved changes.</p>}
        </section>
      </div>
    </>
  )
}

interface Pending { label: string; change: Change }

function describeChanges(pending: Pending[]): string {
  return pending.map((p) => p.label).join('; ')
}

function compileDiagnostics(error: ApiError | null): Diagnostic[] {
  return error ? [{ level: 'error', code: error.code, message: error.message }] : []
}

function Choices({ id, original, shown, editable, pending, pick, variant }: {
  id: string
  original: Manifest
  shown: Manifest
  editable: boolean
  pending: Record<string, Pending>
  pick: (key: string, label: string, original: boolean, change: Change) => void
  variant: boolean
}) {
  const auth = new Set(shown.apis.flatMap((a) => a.downstreamAuth?.supported ?? []))
  return (
    <div className="card">
      <div className="card-head">
        <h2>How it's shaped</h2>
        <Link to={`/servers/${id}/tools`} className="hint">Tools &amp; rules →</Link>
      </div>
      <div className="rows">
        {choices(shown).map((c) => {
          const now = c.current(shown)
          const was = c.current(original)
          return (
            <div key={c.id} style={{ padding: '14px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <span><b style={{ fontSize: 14, fontWeight: 600 }}>{c.title}</b>{pending[c.id] && <span className="tag" style={{ marginLeft: 8 }}>changed</span>}<br />
                <span className="hint">{c.hint}</span></span>
              {editable ? (
                <Segmented label={c.title} value={now} options={c.options.map((o) => [o.value, o.label])}
                  onChange={(v) => pick(c.id, `${c.title}: ${c.options.find((o) => o.value === v)?.label ?? v}`, v === was, c.change(v))} />
              ) : <span className="tag" style={{ alignSelf: 'flex-start' }}>{c.options.find((o) => o.value === now)?.label ?? now}</span>}
            </div>
          )
        })}
        <div style={{ padding: '14px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <span><b style={{ fontSize: 14, fontWeight: 600 }}>Extra tools in the package</b><br /><span className="hint">Support packs: audit, explanations, checks.</span></span>
          <div className="actions">
            {PACKS.map(([pack, label]) => {
              const on = packOn(shown, pack)
              const key = 'pack:' + pack
              return (
                <button key={pack} type="button" className="pill" aria-pressed={on} disabled={!editable}
                  onClick={() => pick(key, `${label}: ${on ? 'off' : 'on'}`, !on === packOn(original, pack), packChange(pack, !on, variant))}>{label}</button>
              )
            })}
          </div>
        </div>
        <div style={{ padding: '14px 0' }}>
          <b style={{ fontSize: 14, fontWeight: 600 }}>Sign-in to the API</b><br />
          <span className="hint">{[...auth].map((a) => AUTH_LABELS[a] ?? a).join(', ') || 'None'} · each target server picks one · <Link to={`/servers/${id}/connections`}>Connections</Link></span>
        </div>
      </div>
    </div>
  )
}

function DefinitionDiff({ id, version, after }: { id: string; version: number; after?: string }) {
  const detail = useQuery({ queryKey: ['version', id, version], queryFn: () => api.version(id, version) })
  const root = detail.data?.root
  const before = useQuery({ queryKey: ['file', id, version, root], queryFn: () => api.file(id, version, root!), enabled: !!root })
  if (!before.data || after === undefined) return <Loading what="the changes" />
  const lines = diffLines(before.data, after)
  return (
    <>
      <div>
        <div className="eyebrow">What gets saved</div>
        <h3 className="mono">{root}</h3>
      </div>
      <pre className="code" aria-label="Changes to the definition">
        {lines.map((l, i) => (
          <span key={i} style={{ display: 'block', color: l.kind === '+' ? 'var(--note-ok-ink)' : l.kind === '-' ? 'var(--note-bad-ink)' : undefined }}>
            {l.kind === '…' ? '…' : `${l.kind} ${l.text}`}
          </span>
        ))}
      </pre>
      <p className="hint">Only these lines change; the rest of the file, its comments and the other files stay as they are.</p>
    </>
  )
}

export function agentView(tool: Tool) {
  return { name: tool.name, title: tool.title, description: tool.description, inputSchema: tool.inputSchema, annotations: tool.annotations }
}

function AgentPreview({ manifest }: { manifest: Manifest }) {
  const listed = listedTools(manifest)
  const first = listed.find((t) => t.kind === 'api') ?? listed[0]
  return (
    <>
      <div style={{ display: 'flex', gap: 14, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="big">{listed.length}</span>
        <span className="hint">tools in tools/list · {manifest.tools.length} in all · {EXPOSURE[manifest.exposure.mode] ?? manifest.exposure.mode}</span>
      </div>
      {manifest.server.instructions && <div className="stage-card"><span className="eyebrow">Instructions</span><p>{manifest.server.instructions}</p></div>}
      <div className="list">
        {listed.slice(0, 8).map((t) => (
          <div key={t.name} className="stage-card" style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            <span className="mono" style={{ color: 'var(--stage-bright)', fontSize: 13 }}>{t.name}</span>
            <span className="meta">{t.title}</span>
          </div>
        ))}
        {listed.length > 8 && <span className="hint">+ {listed.length - 8} more</span>}
      </div>
      {first && <pre className="code" aria-label={`${first.name} as the agent gets it`}>{JSON.stringify(agentView(first), null, 2)}</pre>}
    </>
  )
}

export function CustomerPreview({ id, version = 'latest', text }: { id: string; version?: string | number; text?: string }) {
  const saved = useQuery({ queryKey: ['example-config', id, String(version)], queryFn: () => api.exampleConfig(id, version), retry: false, enabled: text === undefined })
  const example = text === undefined ? saved : { data: text, error: null }
  return (
    <>
      <div className="card-head">
        <div>
          <div className="eyebrow">Your customer will configure</div>
          <h3>The example configuration in the package</h3>
        </div>
        <span className="example">Example data</span>
      </div>
      <ErrorBox error={example.error} />
      {example.data && <pre className="code">{example.data}</pre>}
      <p className="hint">Hosts and names are placeholders; the customer puts in their own. Secrets are only references to their .env.</p>
    </>
  )
}

function PackagePreview({ id, manifest }: { id: string; manifest: Manifest }) {
  const builds = useQuery({ queryKey: ['builds', id], queryFn: () => api.builds(id) })
  const last = builds.data?.items.find((b) => b.status === 'succeeded' && !b.deleted)
  const files = useQuery({ queryKey: ['artifacts', last?.id], queryFn: () => api.artifacts(last!.id), enabled: !!last })
  const name = `${manifest.server.name}-${manifest.server.version}`
  return (
    <>
      <div>
        <div className="eyebrow">What you hand to your customer</div>
        <h3 className="mono">{last ? last.artifacts.find((a) => a.name.endsWith('.zip'))?.name : name + '.zip'}</h3>
      </div>
      {!last && <p className="hint">Not built yet. Build to see the package's files, signed and checksummed.</p>}
      {files.data?.packageFiles && <pre className="code">{files.data.packageFiles.map((f) => f.path).join('\n')}</pre>}
      <div className="note note-ok">Works fully offline: no license call, no telemetry to you, no downloads at runtime.</div>
      {last && <Link to={`/servers/${id}/builds?build=${last.id}`}>Open build {last.serverVersion} →</Link>}
    </>
  )
}

export function Checks({ diagnostics }: { diagnostics: Diagnostic[] }) {
  const c = counts({ diagnostics } as Manifest)
  const shown = diagnostics.filter((d) => d.level !== 'info')
  return (
    <>
      <div className={'note ' + (c.error ? 'note-bad' : c.warning ? 'note-warn' : 'note-ok')}>
        {c.error ? `${c.error} error${c.error === 1 ? '' : 's'}` : 'Compiles'}{c.warning ? ` · ${c.warning} warning${c.warning === 1 ? '' : 's'}` : ''} · {c.info} notes
      </div>
      <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {shown.map((d, i) => (
          <li key={i} className="stage-card">
            <span className="mono" style={{ fontSize: 12, color: d.level === 'error' ? 'var(--note-bad-ink)' : 'var(--note-warn-ink)' }}>{d.level} {d.code}</span>
            <span>{d.message}</span>
            {d.location && <span className="meta mono">{typeof d.location === 'string' ? d.location : `${d.location.file ?? ''}${d.location.pointer ? '#' + d.location.pointer : ''}`}</span>}
          </li>
        ))}
      </ul>
      {c.info > 0 && <p className="hint">{c.info} notes (for example operations left out on purpose) are on Sources.</p>}
    </>
  )
}
