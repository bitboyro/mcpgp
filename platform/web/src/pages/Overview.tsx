import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, type Diagnostic, type Manifest, type Tool } from '../api'
import { AUTH_LABELS, EXPOSURE, apiTools, counts, listedTools, useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, Segmented, ago } from '../ui'

type Tab = 'agent' | 'customer' | 'package' | 'checks'

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
  const server = useServer(id)
  const manifest = useManifest(id, 'latest', server.data?.latestVersion != null)
  const [tab, setTab] = useState<Tab>('agent')
  const latest = server.data?.versions.at(-1)
  const compileError = manifest.error instanceof ApiError ? manifest.error : null

  return (
    <>
      <PageHead title={manifest.data?.server.title ?? server.data?.title ?? id}
        sub="What this server is, and what it becomes. The right side shows the server you'll ship.">
        <Link to={`/servers/${id}/sources`} className="btn">New version</Link>
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={server.error} />
          {server.isLoading && <Loading />}
          {server.data && (
            <div className="card">
              <h2>Definition</h2>
              <dl className="kv">
                <dt>Server</dt><dd className="mono">{manifest.data?.server.name ?? id} {manifest.data?.server.version}</dd>
                <dt>Version</dt><dd>{latest ? <>{latest.number} · by {latest.author} {ago(latest.created)}{latest.message ? ` · ${latest.message}` : ''}</> : 'none yet'}</dd>
                {server.data.parent && <><dt>Variant of</dt><dd><Link to={`/servers/${server.data.parent}`}>{server.data.parent}</Link></dd></>}
                {manifest.data?.build.extends && <><dt>Extends</dt><dd className="mono">{manifest.data.build.extends.name}{manifest.data.build.extends.version ? '@' + manifest.data.build.extends.version : ''}</dd></>}
              </dl>
            </div>
          )}
          {compileError && (
            <div className="card">
              <h2>This version doesn't compile</h2>
              <p className="hint">{compileError.message}. The checks on <Link to={`/servers/${id}/sources`}>Sources</Link> say why.</p>
            </div>
          )}
          {manifest.data && <Choices manifest={manifest.data} id={id} />}
        </section>
        <section className="stage" aria-label="Preview">
          <div className="card-head">
            <Segmented role="tab" label="Preview" value={tab} onChange={setTab}
              options={[['agent', 'Agent'], ['customer', 'Customer setup'], ['package', 'Package'], ['checks', 'Checks']]} />
            <span className="live">Live</span>
          </div>
          {manifest.isLoading && <Loading what="the preview" />}
          {manifest.data && tab === 'agent' && <AgentPreview manifest={manifest.data} />}
          {manifest.data && tab === 'customer' && <CustomerPreview id={id} />}
          {manifest.data && tab === 'package' && <PackagePreview id={id} manifest={manifest.data} />}
          {tab === 'checks' && <Checks diagnostics={manifest.data?.diagnostics ?? compileDiagnostics(compileError)} />}
        </section>
      </div>
    </>
  )
}

function compileDiagnostics(error: ApiError | null): Diagnostic[] {
  return error ? [{ level: 'error', code: error.code, message: error.message }] : []
}

function Choices({ manifest, id }: { manifest: Manifest; id: string }) {
  const tools = apiTools(manifest)
  const readOnly = tools.filter((t) => t.annotations?.readOnlyHint).length
  const auth = new Set(manifest.apis.flatMap((a) => a.downstreamAuth?.supported ?? []))
  const rows: [string, string, string][] = [
    ['What the agent sees', EXPOSURE[manifest.exposure.mode] ?? manifest.exposure.mode,
      manifest.exposure.mode === 'hybrid' ? `${manifest.exposure.pinned?.length ?? 0} pinned, the rest found by search` : `${listedTools(manifest).length} tools in tools/list`],
    ['What it may do', readOnly === tools.length ? 'Read only' : 'Read and write', `${readOnly} of ${tools.length} API tools only read`],
    ['Target servers', manifest.instances.mode === 'multi' ? 'Several, per call' : 'One server',
      manifest.instances.mode === 'multi' ? `mixed versions: ${manifest.instances.mixedContracts ?? 'union'}` : 'tool inputs carry only the API’s parameters'],
    ['Sign-in to the API', [...auth].map((a) => AUTH_LABELS[a] ?? a).join(', ') || 'None', 'each target server picks one'],
    ['How agents connect', (manifest.runtime.transports?.allowed ?? []).join(' and ') || 'stdio', `default ${manifest.runtime.transports?.default ?? 'stdio'}`],
    ['Safety', manifest.policies.confirmDestructive ? 'Confirm destructive calls' : 'No confirmation', `responses up to ${manifest.policies.responseLimitKb ?? '—'} KB`],
  ]
  return (
    <div className="card">
      <div className="card-head">
        <h2>How it's shaped</h2>
        <Link to={`/servers/${id}/tools`} className="hint">Tools &amp; rules →</Link>
      </div>
      <div className="rows">
        {rows.map(([title, value, hint]) => (
          <div key={title} style={{ padding: '12px 0', display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
            <span><b style={{ fontSize: 14, fontWeight: 600 }}>{title}</b><br /><span className="hint">{hint}</span></span>
            <span className="tag" style={{ alignSelf: 'center' }}>{value}</span>
          </div>
        ))}
      </div>
      <p className="hint">These come from the definition. Change them in its files and upload a new version on Sources.</p>
    </div>
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

export function CustomerPreview({ id, version = 'latest' }: { id: string; version?: string | number }) {
  const example = useQuery({ queryKey: ['example-config', id, String(version)], queryFn: () => api.exampleConfig(id, version), retry: false })
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
