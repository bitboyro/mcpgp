import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { api, type Build, type LibraryItem, type ServerSummary } from '../api'
import { readPicked, type PickedProject } from '../files'
import { nameClashes, specProblems } from '../specUpload'
import { ConnectAgent } from './ConnectAgent'
import { NameClashesView, SpecPicker, SpecProblemsView, type SpecUpload } from './SpecUpload'
import { useSession } from '../session'
import { BuildChip, Chip, ErrorBox, Icon, Loading, PageHead, Segmented, slug } from '../ui'

type Panel = { mode: 'copy' | 'variant'; source: ServerSummary } | { mode: 'new' } | { mode: 'connect' } | null

const KIND_LABELS: Record<string, string> = {
  contracts: 'API contracts', naming: 'Naming policies', rules: 'Rule sets', packs: 'Pack configs', templates: 'Templates',
}

export function ServersPage() {
  const { settings, can } = useSession()
  const [search] = useSearchParams()
  // undefined: nothing chosen yet; ?connect, or a platform with none of its own servers, starts at Connect an agent
  const [chosen, setPanel] = useState<Panel | undefined>(search.has('connect') ? { mode: 'connect' } : undefined)
  const servers = useQuery({ queryKey: ['servers'], queryFn: () => api.servers() })
  // the platform's own server first
  const items = [...(servers.data?.items ?? [])].sort((a, b) => Number(!!b.system) - Number(!!a.system))
  const own = items.filter((s) => !s.system)
  const firstRun = servers.isSuccess && own.length === 0 && items.length > 0
  const panel = chosen === undefined ? (firstRun ? { mode: 'connect' as const } : null) : chosen
  const manifests = useQueries({
    queries: items.map((s) => ({ queryKey: ['manifest', s.id, 'latest'], queryFn: () => api.manifest(s.id), enabled: s.latestVersion != null, retry: false })),
  })
  const builds = useQueries({ queries: items.map((s) => ({ queryKey: ['builds', s.id], queryFn: () => api.builds(s.id) })) })

  return (
    <>
      <PageHead title="All servers"
        sub={<>{own.length} MCP server{own.length === 1 ? '' : 's'} in {settings.workspace}{items.length > own.length ? ', and the platform’s own' : ''}. Duplicate one, or make a variant that stays in sync with it.</>}>
        {can('servers:write') && <button type="button" className="btn btn-primary" onClick={() => setPanel({ mode: 'new' })}>New server</button>}
      </PageHead>
      <div className="body">
        <section aria-label="Servers" className="server-grid">
          {servers.isLoading && <Loading what="servers" />}
          <ErrorBox error={servers.error} />
          {servers.isSuccess && own.length === 0 && (
            <div className="card"><h2>No servers yet</h2><p className="hint">Start from your API's OpenAPI or Swagger file (JSON or YAML): <b>New server</b>.</p>
              {can('servers:write') && <button type="button" className="btn btn-primary" style={{ alignSelf: 'flex-start' }} onClick={() => setPanel({ mode: 'new' })}>New server from a spec</button>}</div>
          )}
          {items.map((server, i) => {
            const manifest = manifests[i]?.data
            const serverBuilds: Build[] = builds[i]?.data?.items ?? []
            const last = serverBuilds[0]
            const lastGood = serverBuilds.find((b) => b.status === 'succeeded')
            const variants = items.filter((s) => s.parent === server.id).length
            const selected = panel && 'source' in panel ? panel.source.id === server.id : !!(panel?.mode === 'connect' && server.system)
            return (
              <article key={server.id} className={'server-card' + (selected ? ' selected' : '')} aria-label={server.id}>
                <div className="card-head">
                  <Link to={`/servers/${server.id}`} className="name">{server.id}</Link>
                  {server.system ? <Chip tone="new">Made by the platform</Chip> : last ? <BuildChip build={last} /> : <Chip tone={server.latestVersion == null ? 'idle' : 'new'}>{server.latestVersion == null ? 'Draft' : 'Not built'}</Chip>}
                </div>
                <div>
                  <div className="sub">{manifest?.server.title ?? server.title}</div>
                  {server.parent && <div className="lineage">↳ variant of {server.parent}</div>}
                  {server.system && <div className="lineage">The platform as tools, for your agents. Updated with every platform release.</div>}
                  {manifests[i]?.error && <div className="lineage">doesn't compile yet; see Sources</div>}
                </div>
                <div className="stats">
                  <span className="stat"><b>{manifest ? manifest.tools.length : '—'}</b><span>tools</span></span>
                  <span className="stat"><b>{lastGood?.serverVersion ?? '—'}</b><span>last build</span></span>
                  <span className="stat"><b>{variants}</b><span>variants</span></span>
                </div>
                <div className="card-actions">
                  <Link to={`/servers/${server.id}`} className="btn btn-dark btn-sm">Open</Link>
                  {server.system && <button type="button" className="btn btn-sm btn-primary" onClick={() => setPanel({ mode: 'connect' })}>Connect an agent</button>}
                  {can('servers:write') && <>
                    {!server.system && <button type="button" className="btn btn-sm" onClick={() => setPanel({ mode: 'copy', source: server })}>Duplicate</button>}
                    <button type="button" className="btn btn-sm btn-soft" onClick={() => setPanel({ mode: 'variant', source: server })}>New variant</button>
                  </>}
                </div>
              </article>
            )
          })}
        </section>
        <aside aria-label="Side panel" className="stage stage-narrow">
          {panel?.mode === 'new' ? <NewServer onClose={() => setPanel(null)} />
            : panel?.mode === 'connect' ? <ConnectAgent onClose={() => setPanel(null)} />
            : panel ? <CopyPanel key={panel.source.id + panel.mode} mode={panel.mode} source={panel.source} onClose={() => setPanel(null)}
              onMode={(mode) => setPanel({ mode, source: panel.source })} />
            : <SharedLibrary />}
        </aside>
      </div>
    </>
  )
}

function SharedLibrary() {
  const library = useQuery({ queryKey: ['library'], queryFn: api.library })
  const byKind = new Map<string, LibraryItem[]>()
  for (const item of library.data?.items ?? []) byKind.set(item.kind, [...(byKind.get(item.kind) ?? []), item])
  return (
    <>
      <div>
        <h2>Shared in this workspace</h2>
        <p className="hint">Defined once, used by several servers.</p>
      </div>
      <div className="list">
        {[...byKind.entries()].map(([kind, items]) => (
          <Link key={kind} to={`/library#${kind}`} className="stage-link">
            <span><span>{KIND_LABELS[kind] ?? kind}</span><br /><span className="meta">{items.map((i) => i.name).join(' · ')}</span></span>
            <span className="mono">{items.length}</span>
          </Link>
        ))}
        {library.isSuccess && byKind.size === 0 && <p className="empty">Nothing shared yet.</p>}
      </div>
      <div className="stage-card">
        <p><b>Duplicate</b> makes an independent copy. <b>Variant</b> extends the original and keeps only your differences,
          so fixes to the original flow into every variant.</p>
      </div>
    </>
  )
}

function CopyPanel({ mode, source, onClose, onMode }: { mode: 'copy' | 'variant'; source: ServerSummary; onClose: () => void; onMode: (m: 'copy' | 'variant') => void }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { settings } = useSession()
  const [id, setId] = useState(source.id + (mode === 'variant' ? '-variant' : '-copy'))
  const [pin, setPin] = useState(false)
  const create = useMutation({
    mutationFn: () => mode === 'variant' ? api.variant(source.id, { id, pin }) : api.duplicate(source.id, { id }),
    onSuccess: async (server) => {
      await queryClient.invalidateQueries({ queryKey: ['servers'] })
      navigate(`/servers/${server.id}`)
    },
  })
  const isVariant = mode === 'variant'
  const summary = isVariant
    ? [`# ${id}.mcpgp.yaml`, `extends: mcpgp://${settings.workspace}/servers/${source.id}@${pin ? source.latestVersion : 'latest'}`, '', '# everything else is inherited;',
      '# add only your differences', pin ? '# pinned: updates to the original wait until you move the pin' : '# follows every new version of the original'].join('\n')
    : [`Copies ${source.id} version ${source.latestVersion} as it is now:`, '  every file of the project', '  not copied: builds, activity'].join('\n')
  return (
    <form className="list" style={{ gap: 16 }} onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
      <div className="card-head">
        <div>
          <div className="eyebrow">From {source.id}</div>
          <h2>{isVariant ? 'New variant' : 'Duplicate'}</h2>
        </div>
        <button type="button" className="btn btn-stage btn-sm" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
      </div>
      <Segmented label="Kind" value={mode} onChange={onMode} options={[['variant', 'Variant'], ['copy', 'Duplicate']]} />
      <p className="hint">{isVariant ? 'Extends the original. Only your differences are stored.' : 'Independent copy. Diverges from here on.'}</p>
      <label className="field">Name
        <input className="input input-stage" value={id} onChange={(e) => setId(slug(e.target.value))} required pattern="[a-z0-9][a-z0-9-]{1,62}" />
      </label>
      {isVariant && (
        <Segmented label="Follow the original" value={pin ? 'pin' : 'latest'} onChange={(v) => setPin(v === 'pin')}
          options={[['latest', 'Follow latest'], ['pin', `Pin version ${source.latestVersion}`]]} />
      )}
      <div className="stage-card">
        <span className="eyebrow">{isVariant ? 'What gets stored' : 'What gets copied'}</span>
        <pre className="code code-wrap" style={{ border: 0, padding: 0 }}>{summary}</pre>
      </div>
      <ErrorBox error={create.error} />
      <button type="submit" className="btn btn-primary" disabled={create.isPending}>{isVariant ? 'Create variant' : 'Create copy'}</button>
    </form>
  )
}

function NewServer({ onClose }: { onClose: () => void }) {
  const [source, setSource] = useState<'spec' | 'project'>('spec')
  return (
    <div className="list" style={{ gap: 16 }}>
      <div className="card-head">
        <div>
          <div className="eyebrow">New server</div>
          <h2>{source === 'spec' ? 'From your API\u2019s spec' : 'From a definition project'}</h2>
        </div>
        <button type="button" className="btn btn-stage btn-sm" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
      </div>
      <Segmented label="Start from" value={source} onChange={setSource}
        options={[['spec', 'An OpenAPI or Swagger file'], ['project', 'A definition project']]} />
      {source === 'spec' ? <NewFromSpec /> : <NewFromProject />}
    </div>
  )
}

/** A server from an uploaded spec: checked by a dry run as files are picked, created as version 1. */
function NewFromSpec() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [files, setFiles] = useState<SpecUpload | null>(null)
  const [rev, setRev] = useState(0)
  const [main, setMain] = useState('')
  const [id, setId] = useState<string | null>(null)
  const [title, setTitle] = useState<string | null>(null)
  const [version, setVersion] = useState<string | null>(null)
  // what the user chose for clashing tool names: names per operation, or the server's rule
  const [renames, setRenames] = useState<Record<string, string> | null>(null)
  const [rule, setRule] = useState(false)
  const [template, setTemplate] = useState('default')
  const [acceptedTemplate, setAcceptedTemplate] = useState<string | null>(null)
  const naming = { collisions: rule ? 'method-path' : undefined, renames: renames ? JSON.stringify(renames) : undefined }
  const plan = useQuery({
    queryKey: ['from-spec', rev, main, naming.collisions, naming.renames, template],
    queryFn: () => api.fromSpecPlan(files!, { main: main || undefined, template: template === 'default' ? undefined : template, ...naming }),
    enabled: files !== null,
    retry: false,
  })
  const problems = specProblems(plan.error)
  const clashes = nameClashes(plan.error)
  const create = useMutation({
    mutationFn: () => api.createFromSpec(files!, { main: main || undefined, id: id ?? undefined, title: title ?? undefined, version: version ?? undefined,
      template: template === 'default' ? undefined : template, acceptTemplate: acceptedTemplate ?? undefined, ...naming }),
    onSuccess: async (server) => {
      await queryClient.invalidateQueries({ queryKey: ['servers'] })
      navigate(`/servers/${server.id}`)
    },
  })
  const shownId = id ?? plan.data?.id ?? ''
  return (
    <form className="list" style={{ gap: 14 }} onSubmit={(e) => { e.preventDefault(); if (plan.data && shownId) create.mutate() }}>
      <p className="hint">The platform makes the server's definition from the spec: one tool per operation, named from the spec, ready to adjust
        on its Overview. Nothing is created until you press Create.</p>
      <SpecPicker files={files} onFiles={(f) => { setFiles(f); setRev((r) => r + 1); setRenames(null); setRule(false); setAcceptedTemplate(null); create.reset() }} />
      {plan.isFetching && <p className="hint">Reading the spec…</p>}
      {problems && <SpecProblemsView problems={problems} main={main} onMain={setMain} />}
      {clashes && clashes.length > 0 && (
        <NameClashesView clashes={clashes} prefix={clashes[0].suggested.slice(0, clashes[0].suggested.length - clashes[0].base.length - 1)}
          onRenames={(r) => { setRenames({ ...(renames ?? {}), ...r }); setRule(false) }} onRule={() => { setRule(true); setRenames(null) }} />
      )}
      {plan.error && !problems && !(clashes && clashes.length > 0) && <ErrorBox error={plan.error} />}
      {plan.data && (
        <>
          <div className="stage-card">
            <span className="eyebrow">Found</span>
            <span><b>{plan.data.title}</b> · {FORMATS[plan.data.format] ?? plan.data.format} · version {plan.data.version}</span>
            <span className="hint">{plan.data.tools === 1 ? '1 operation becomes a tool' : `${plan.data.tools} operations become tools`} · main file <span className="mono">{plan.data.main}</span>
              {plan.data.files.filter((f) => f.startsWith('contracts/')).length > 1 && ` and ${plan.data.files.filter((f) => f.startsWith('contracts/')).length - 1} more`}</span>
            {plan.data.moved.length > 0 && (
              <span className="hint">Put where the spec refers to them: {plan.data.moved.map((m) => `${m.from} → ${m.to}`).join(', ')}</span>
            )}
          </div>
          <label className="field">Configuration template
            <select className="input input-stage" value={template} onChange={(e) => { setTemplate(e.target.value); setAcceptedTemplate(null) }}>
              <option value="blank">Blank</option><option value="default">Default</option><option value="personal-use">Personal use</option><option value="enterprise-grade">Enterprise grade</option>
            </select>
          </label>
          {plan.data.template && <div className="stage-card"><b>{plan.data.template.title}</b><span className="hint">{plan.data.template.description}</span>
            {plan.data.template.requiredInputs.length > 0 && <span className="hint">Before deployment: {plan.data.template.requiredInputs.join(' · ')}</span>}
            <label className="check"><input type="checkbox" checked={acceptedTemplate === plan.data.acceptTemplate}
              onChange={(e) => setAcceptedTemplate(e.target.checked ? plan.data.acceptTemplate ?? null : null)} /> I reviewed and accept this template</label></div>}
          <label className="field">Server id (in addresses and references; lowercase, digits, -)
            <input className="input input-stage" value={shownId} onChange={(e) => setId(slug(e.target.value))} required pattern="[a-z0-9][a-z0-9-]{1,62}" />
          </label>
          {plan.data.exists && id === null && <p className="hint">A server called {plan.data.id} exists already; choose another id.</p>}
          <label className="field">Title
            <input className="input input-stage" value={title ?? plan.data.title} onChange={(e) => setTitle(e.target.value)} />
          </label>
          <label className="field">Contract version (from the spec's info.version)
            <input className="input input-stage" value={version ?? plan.data.version} onChange={(e) => setVersion(e.target.value)} />
          </label>
          {(rule || renames) && <p className="hint">{rule ? 'Clashing tools are named from their method and path (naming.collisions: method-path).'
            : `Renamed: ${Object.values(renames!).join(', ')}.`} <button type="button" className="btn btn-sm" onClick={() => { setRule(false); setRenames(null) }}>Change</button></p>}
          <ErrorBox error={create.error} />
          <button type="submit" className="btn btn-primary" disabled={!shownId || create.isPending || (template !== 'default' && acceptedTemplate !== plan.data.acceptTemplate)}>{create.isPending ? 'Creating…' : 'Create server'}</button>
        </>
      )}
    </form>
  )
}

const FORMATS: Record<string, string> = { 'swagger-2.0': 'Swagger 2.0', 'openapi-3.0': 'OpenAPI 3.0', 'openapi-3.1': 'OpenAPI 3.1' }

function NewFromProject() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const folder = useRef<HTMLInputElement>(null)
  const zip = useRef<HTMLInputElement>(null)
  const [picked, setPicked] = useState<PickedProject | null>(null)
  const [id, setId] = useState('')
  const create = useMutation({
    mutationFn: () => api.createServer(id, picked!.files),
    onSuccess: async (server) => {
      await queryClient.invalidateQueries({ queryKey: ['servers'] })
      navigate(`/servers/${server.id}`)
    },
  })
  const pick = async (list: FileList | null) => {
    if (!list || list.length === 0) return
    const project = await readPicked(list)
    setPicked(project)
    if (!id) setId(slug(project.name))
  }
  return (
    <form className="list" style={{ gap: 16 }} onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
      <p className="hint">For a project kept in git: a folder with one <code>*.mcpgp.yaml</code> at the top, its contracts, overlays and rules.
        Files are checked like the CLI checks them; hidden files are left out.</p>
      <div className="actions">
        <button type="button" className="btn btn-stage" onClick={() => folder.current?.click()}>Choose folder</button>
        <button type="button" className="btn btn-stage" onClick={() => zip.current?.click()}>Choose zip</button>
        <input ref={folder} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true"
          {...{ webkitdirectory: '', directory: '' }} multiple onChange={(e) => pick(e.target.files)} />
        <input ref={zip} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true" accept=".zip,application/zip"
          onChange={(e) => pick(e.target.files)} />
      </div>
      {picked && (
        <div className="stage-card">
          <span className="eyebrow">Picked</span>
          <span>{picked.files instanceof Blob ? 'a zip' : `${picked.count} files`} from <span className="mono">{picked.name}</span></span>
        </div>
      )}
      <label className="field">Server id
        <input className="input input-stage" value={id} onChange={(e) => setId(slug(e.target.value))} required pattern="[a-z0-9][a-z0-9-]{1,62}" />
      </label>
      <ErrorBox error={create.error} />
      <button type="submit" className="btn btn-primary" disabled={!picked || !id || create.isPending}>Create server</button>
    </form>
  )
}
