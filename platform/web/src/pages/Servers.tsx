import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, type Build, type LibraryItem, type ServerSummary } from '../api'
import { readPicked, type PickedProject } from '../files'
import { useSession } from '../session'
import { BuildChip, Chip, ErrorBox, Icon, Loading, PageHead, Segmented, slug } from '../ui'

type Panel = { mode: 'copy' | 'variant'; source: ServerSummary } | { mode: 'new' } | null

const KIND_LABELS: Record<string, string> = {
  contracts: 'API contracts', naming: 'Naming policies', rules: 'Rule sets', packs: 'Pack configs', templates: 'Templates',
}

export function ServersPage() {
  const { settings, can } = useSession()
  const [panel, setPanel] = useState<Panel>(null)
  const servers = useQuery({ queryKey: ['servers'], queryFn: () => api.servers() })
  const items = servers.data?.items ?? []
  const manifests = useQueries({
    queries: items.map((s) => ({ queryKey: ['manifest', s.id, 'latest'], queryFn: () => api.manifest(s.id), enabled: s.latestVersion != null, retry: false })),
  })
  const builds = useQueries({ queries: items.map((s) => ({ queryKey: ['builds', s.id], queryFn: () => api.builds(s.id) })) })

  return (
    <>
      <PageHead title="All servers"
        sub={<>{items.length} MCP server{items.length === 1 ? '' : 's'} in {settings.workspace}. Duplicate one, or make a variant that stays in sync with it.</>}>
        {can('servers:write') && <button type="button" className="btn btn-primary" onClick={() => setPanel({ mode: 'new' })}>New server</button>}
      </PageHead>
      <div className="body">
        <section aria-label="Servers" className="server-grid">
          {servers.isLoading && <Loading what="servers" />}
          <ErrorBox error={servers.error} />
          {servers.isSuccess && items.length === 0 && (
            <div className="card"><h2>No servers yet</h2><p className="hint">Upload a definition project (a folder with a <code>*.mcpgp.yaml</code> at the top, or a zip of it) to start.</p></div>
          )}
          {items.map((server, i) => {
            const manifest = manifests[i]?.data
            const serverBuilds: Build[] = builds[i]?.data?.items ?? []
            const last = serverBuilds[0]
            const lastGood = serverBuilds.find((b) => b.status === 'succeeded')
            const variants = items.filter((s) => s.parent === server.id).length
            const selected = panel && panel.mode !== 'new' && panel.source.id === server.id
            return (
              <article key={server.id} className={'server-card' + (selected ? ' selected' : '')} aria-label={server.id}>
                <div className="card-head">
                  <Link to={`/servers/${server.id}`} className="name">{server.id}</Link>
                  {last ? <BuildChip build={last} /> : <Chip tone={server.latestVersion == null ? 'idle' : 'new'}>{server.latestVersion == null ? 'Draft' : 'Not built'}</Chip>}
                </div>
                <div>
                  <div className="sub">{manifest?.server.title ?? server.title}</div>
                  {server.parent && <div className="lineage">↳ variant of {server.parent}</div>}
                  {manifests[i]?.error && <div className="lineage">doesn't compile yet; see Sources</div>}
                </div>
                <div className="stats">
                  <span className="stat"><b>{manifest ? manifest.tools.length : '—'}</b><span>tools</span></span>
                  <span className="stat"><b>{lastGood?.serverVersion ?? '—'}</b><span>last build</span></span>
                  <span className="stat"><b>{variants}</b><span>variants</span></span>
                </div>
                <div className="card-actions">
                  <Link to={`/servers/${server.id}`} className="btn btn-dark btn-sm">Open</Link>
                  {can('servers:write') && <>
                    <button type="button" className="btn btn-sm" onClick={() => setPanel({ mode: 'copy', source: server })}>Duplicate</button>
                    <button type="button" className="btn btn-sm btn-soft" onClick={() => setPanel({ mode: 'variant', source: server })}>New variant</button>
                  </>}
                </div>
              </article>
            )
          })}
        </section>
        <aside aria-label="Side panel" className="stage stage-narrow">
          {panel?.mode === 'new' ? <NewServer onClose={() => setPanel(null)} />
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
      <div className="card-head">
        <div>
          <div className="eyebrow">Upload a definition</div>
          <h2>New server</h2>
        </div>
        <button type="button" className="btn btn-stage btn-sm" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>
      </div>
      <p className="hint">A project folder with one <code>*.mcpgp.yaml</code> at the top, its contracts, overlays and rules. Files are checked
        like the CLI checks them; hidden files are left out.</p>
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
