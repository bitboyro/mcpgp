import { useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import type { ProvenanceStep, Tool } from '../api'
import { listedTools, useManifest } from '../serverData'
import { useSession } from '../session'
import { Chip, ErrorBox, Loading, PageHead, Segmented } from '../ui'
import { NamingView } from './Naming'
import { OperationsView } from './Operations'
import { ToolEdit } from './ToolEdit'
import { agentView } from './Overview'

type Filter = 'all' | 'listed' | 'api' | 'pack' | 'builtin'

function describe(step: ProvenanceStep): string {
  const value = typeof step.value === 'string' ? step.value : JSON.stringify(step.value)
  return value ?? ''
}

export function ToolsPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const manifest = useManifest(id)
  const [filter, setFilter] = useState<Filter>('all')
  const [search, setSearch] = useState('')

  const tools = useMemo(() => {
    if (!manifest.data) return []
    const listed = new Set(listedTools(manifest.data).map((t) => t.name))
    const needle = search.trim().toLowerCase()
    return manifest.data.tools.filter((t) => (filter === 'all' || (filter === 'listed' ? listed.has(t.name) : t.kind === filter))
      && (!needle || t.name.includes(needle) || (t.title ?? '').toLowerCase().includes(needle) || (t.operation?.key ?? '').toLowerCase().includes(needle)))
  }, [manifest.data, filter, search])

  const selectedName = params.get('tool') ?? tools[0]?.name
  const selected = manifest.data?.tools.find((t) => t.name === selectedName)
  const operations = manifest.data ? manifest.data.diagnostics.filter((d) => d.code === 'OPERATION_EXCLUDED').length : 0
  const apiCount = manifest.data?.tools.filter((t) => t.kind === 'api').length ?? 0
  const view = params.get('view') === 'operations' ? 'operations' : params.get('view') === 'naming' ? 'naming' : 'tools'
  const switcher = (
    <Segmented role="tab" label="View" value={view} onChange={(v) => setParams(v === 'tools' ? {} : { view: v }, { replace: true })}
      options={[['tools', 'Tools'], ['operations', 'Operations'], ['naming', 'Naming']]} />
  )

  if (view === 'naming') {
    return (
      <>
        <PageHead title="Tools & rules" sub="How tools get their names: the policy, the vocabulary, and names the lock file keeps.">{switcher}</PageHead>
        <NamingView id={id} />
      </>
    )
  }

  if (view === 'operations') {
    return (
      <>
        <PageHead title="Tools & rules" sub="Every operation of the contracts. Switch one on to make it a tool, off to leave it out.">{switcher}</PageHead>
        <OperationsView id={id} />
      </>
    )
  }

  return (
    <>
      <PageHead title="Tools & rules"
        sub={manifest.data ? <>{apiCount} of {apiCount + operations} operations become tools. Pick one to see exactly what the agent gets, and why.{' '}
          <Link to="?view=operations">Choose operations</Link></> : undefined}>{switcher}</PageHead>
      <div className="body">
        <section className="col" aria-label="Tools">
          <ErrorBox error={manifest.error} />
          {manifest.isLoading && <Loading what="tools" />}
          {manifest.data && (
            <div className="card">
              <Segmented label="Show" value={filter} onChange={setFilter}
                options={[['all', `All ${manifest.data.tools.length}`], ['listed', 'In tools/list'], ['api', 'API'], ['pack', 'Packs'], ['builtin', 'Built-in']]} />
              <label className="field"><span className="visually-hidden">Search tools</span>
                <input className="input" type="search" placeholder="Search names, titles, operations" value={search} onChange={(e) => setSearch(e.target.value)} />
              </label>
              <div className="tool-list" role="listbox" aria-label="Tools">
                {tools.map((t) => (
                  <button key={t.name} type="button" role="option" aria-selected={t.name === selectedName} className="tool-item"
                    onClick={() => setParams({ tool: t.name }, { replace: true })}>
                    <span>
                      <span className="mono">{t.name}</span>
                      <small>{t.operation ? t.operation.key.replace(/^[^:]+:/, '').replace(':', ' ') : t.pack ? `pack ${t.pack}` : t.title}</small>
                    </span>
                    <span className="tag">{t.kind}</span>
                  </button>
                ))}
                {tools.length === 0 && <p className="empty">No tool matches.</p>}
              </div>
            </div>
          )}
        </section>
        <section className="stage" aria-label="Selected tool">
          {selected ? <ToolDetail key={selected.name} id={id} tool={selected} /> : manifest.data && <p className="empty">Pick a tool.</p>}
          {manifest.data && (
            <p className="hint">Rules live in the definition's files (for example <span className="mono">overrides/*.yaml</span>); see them on{' '}
              <Link to={`/servers/${id}/sources`}>Sources</Link>.</p>
          )}
        </section>
      </div>
    </>
  )
}

function ToolDetail({ id, tool }: { id: string; tool: Tool }) {
  const { can } = useSession()
  const [editing, setEditing] = useState(false)
  const a = tool.annotations ?? {}
  const marks: [string, boolean | undefined][] = [['read only', a.readOnlyHint], ['destructive', a.destructiveHint], ['idempotent', a.idempotentHint],
    ['asks to confirm', tool.confirm], ['sensitive', tool.sensitive]]
  const provenance = Object.entries(tool.provenance ?? {})
  return (
    <>
      <div>
        <div className="eyebrow">What the agent gets</div>
        <h2 className="mono" style={{ overflowWrap: 'anywhere' }}>{tool.name}</h2>
        {tool.title && <p style={{ color: 'var(--stage-bright)' }}>{tool.title}</p>}
      </div>
      {tool.description && <p style={{ whiteSpace: 'pre-line' }}>{tool.description}</p>}
      {editing ? <ToolEdit id={id} tool={tool} onClose={() => setEditing(false)} />
        : can('servers:write') && <button type="button" className="btn btn-sm btn-stage" style={{ alignSelf: 'flex-start' }} onClick={() => setEditing(true)}>Edit title and descriptions</button>}
      <div className="actions">
        {marks.filter(([, on]) => on).map(([label]) => <span key={label} className="tag">{label}</span>)}
        {tool.kind !== 'api' && <Chip tone="new">{tool.kind === 'pack' ? `pack ${tool.pack ?? ''}` : 'built-in'}</Chip>}
      </div>
      {tool.variants && (
        <div className="stage-card">
          <span className="eyebrow">Calls</span>
          {Object.entries(tool.variants).map(([contract, v]) => (
            <span key={contract} className="mono" style={{ fontSize: 13 }}>{contract}: {v.method} {v.path}
              {v.security && v.security.length > 0 && <span className="meta"> · {v.security.map((alt) => alt.map((s) => s.scheme + (s.scopes?.length ? ` (${s.scopes.join(' ')})` : '')).join(' + ')).join(' or ')}</span>}
            </span>
          ))}
        </div>
      )}
      {provenance.length > 0 && (
        <div className="stage-card why">
          <span className="eyebrow">Why it looks like this</span>
          {provenance.map(([field, steps]) => (
            <div key={field}>
              <h3 style={{ fontSize: 13, marginBottom: 4 }}>{field}</h3>
              <ol>
                {steps.map((s, i) => (
                  <li key={i}>
                    <span className="step">{s.step}{s.op ? ` ${s.op}` : ''}</span>
                    <span style={{ flex: '1 1 200px', minWidth: 0, overflowWrap: 'anywhere' }}>{describe(s)}</span>
                    {s.file && <span className="meta mono">{s.file}{s.pointer ? '#' + s.pointer : ''}</span>}
                  </li>
                ))}
              </ol>
            </div>
          ))}
        </div>
      )}
      <pre className="code" aria-label="As the agent gets it">{JSON.stringify(agentView(tool), null, 2)}</pre>
    </>
  )
}
