import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, type LibraryItem } from '../api'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, ago } from '../ui'
import { NoticeSetEditor } from './NoticeSetEditor'
import { PolicyEditor } from './PolicyEditor'

const KINDS: [string, string, string][] = [
  ['contracts', 'API contracts', 'OpenAPI and Swagger documents several servers ship.'],
  ['naming', 'Naming policies', 'How operation ids become tool names.'],
  ['notices', 'Shared notices', 'Company policy and disclaimers on the tools of every server that includes them.'],
  ['rules', 'Rule sets', 'Descriptions, safety and selection rules.'],
  ['packs', 'Pack configs', 'Settings for the extra tools in packages.'],
  ['templates', 'Templates', 'Starting configurations: Bare, Default, Basic and Enterprise.'],
]

export function LibraryPage() {
  const library = useQuery({ queryKey: ['library'], queryFn: api.library })
  const items = library.data?.items ?? []
  const [params, setParams] = useSearchParams()
  const open = items.find((i) => params.get('item') === `${i.kind}/${i.name}` || (i.kind === 'naming' && i.name === params.get('naming')) || (i.kind === 'notices' && i.name === params.get('notices')))
  return (
    <>
      <PageHead title="Shared Library"
        sub="Reusable examples and shared configuration for your MCP servers." />
      <div className="body">
        <section className="col col-wide" aria-label="Library">
          <ErrorBox error={library.error} />
          {library.isLoading && <Loading what="the library" />}
          {KINDS.map(([kind, title, hint]) => {
            const ofKind = items.filter((i) => i.kind === kind)
            if (kind === 'templates') {
              const order = ['bare', 'default', 'basic', 'enterprise']
              ofKind.sort((a, b) => (order.includes(a.name) ? order.indexOf(a.name) : 4) - (order.includes(b.name) ? order.indexOf(b.name) : 4))
            }
            return (
              <div key={kind} id={kind} className="card">
                <div className="card-head"><h2>{title}</h2><span className="tag">{ofKind.length}</span></div>
                <p className="hint">{hint}</p>
                {ofKind.map((item) => <Item key={item.name} item={item}
                  open={() => setParams({ item: `${item.kind}/${item.name}` })}
                  selected={item === open} />)}
                {kind === 'notices' && <NewNoticeSet onCreated={(name) => setParams({ notices: name })} />}
              </div>
            )
          })}
        </section>
        <aside className="stage stage-narrow" aria-label={open ? (open.kind === 'naming' ? 'Naming policy' : open.kind === 'notices' ? 'Shared notices' : 'Configuration') : 'How to add'}>
          {open ? <>
            <button type="button" className="btn btn-sm btn-stage" style={{ alignSelf: 'flex-start' }} onClick={() => setParams({})}>Close</button>
            {open.kind === 'naming' ? <PolicyEditor key={open.name} item={open} /> : open.kind === 'notices' ? <NoticeSetEditor key={open.name} item={open} /> : <ItemPreview key={open.kind + '/' + open.name} item={open} />}
          </> : <>
          <h2>Start with an example</h2>
          <p className="hint">Open an entry to inspect its configuration. Starter examples are available in every category and are never applied automatically.</p>
          <div className="stage-card"><h3>Four starting points</h3>
            <p className="hint"><b>Bare</b> starts with no API tools enabled. <b>Default</b> keeps generated settings. <b>Basic</b> is local and read-only. <b>Enterprise</b> starts with explicit tool selection, audit and bounded calls.</p>
            <p className="hint">Enterprise is a starting configuration, not a compliance certification. Configure authentication and review access before deployment.</p>
          </div>
          <p className="hint">The API example follows <a href="https://spec.openapis.org/oas/v3.1.1.html" target="_blank" rel="noreferrer">OpenAPI 3.1</a>. Tool guidance follows <a href="https://modelcontextprotocol.io/specification/2025-06-18/schema" target="_blank" rel="noreferrer">MCP annotations</a>; naming and presets are mcpgp conventions.</p>
          <p className="hint">Your changes are saved as new versions. Restarts preserve existing entries and edits.</p>
          </>}
        </aside>
      </div>
    </>
  )
}

/** A new set of shared notices, with its first notice (a set has at least one). */
function NewNoticeSet({ onCreated }: { onCreated: (name: string) => void }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const [shown, setShown] = useState(false)
  const [name, setName] = useState('')
  const [title, setTitle] = useState('')
  const [text, setText] = useState('')
  const create = useMutation({
    mutationFn: () => api.createLibraryItem('notices', name, title || name, {
      [`${name}.yaml`]: `title: ${JSON.stringify(title || name)}\nnotices:\n  - id: ${name}\n    text: ${JSON.stringify(text.trim())}\n`,
    }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['library'] })
      setShown(false)
      onCreated(name)
      setName(''); setTitle(''); setText('')
    },
  })
  if (!can('library:write')) return null
  if (!shown) return <button type="button" className="btn btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setShown(true)}>New shared notices</button>
  const validName = /^[a-z0-9][a-z0-9-]{0,62}$/.test(name)
  return (
    <form className="list" aria-label="New shared notices" onSubmit={(e) => { e.preventDefault(); if (validName && text.trim()) create.mutate() }}>
      <label className="field">Name (in references: <span className="mono">notices/{name || 'company-policy'}</span>)
        <input className="input mono" value={name} placeholder="company-policy" onChange={(e) => setName(e.target.value.toLowerCase())} />
      </label>
      <label className="field">Title
        <input className="input" value={title} placeholder="Company policy" onChange={(e) => setTitle(e.target.value)} />
      </label>
      <label className="field">First notice, on every tool (more, and narrower ones, after creating it)
        <textarea className="input" rows={3} maxLength={500} style={{ padding: 10 }} value={text} onChange={(e) => setText(e.target.value)}
          placeholder="Company policy: customer data stays in the EU." />
      </label>
      <ErrorBox error={create.error} />
      <div className="actions">
        <button type="submit" className="btn btn-primary" disabled={!validName || !text.trim() || create.isPending}>Create</button>
        <button type="button" className="btn" onClick={() => setShown(false)}>Cancel</button>
      </div>
    </form>
  )
}

function Item({ item, open, selected }: { item: LibraryItem; open?: () => void; selected?: boolean }) {
  const latest = item.versions.at(-1)
  return (
    <div className="row" style={selected ? { background: 'var(--accent-soft)' } : undefined}>
      <span style={{ minWidth: 0 }}>
        <b>{item.title}</b> {item.example && <span className="tag">Starter example</span>}<br />
        {item.description && <span className="hint" style={{ display: 'block', margin: '6px 0' }}>{item.description}</span>}
        <span className="meta mono" style={{ overflowWrap: 'anywhere' }}>{item.reference}</span>
      </span>
      <span className="actions">
        <span className="meta">{item.versions.length} version{item.versions.length === 1 ? '' : 's'} · {latest ? `${latest.author} ${ago(latest.created)}` : ''}</span>
        {open && <button type="button" className="btn btn-sm" onClick={open}>Open</button>}
      </span>
    </div>
  )
}

/** Read-only source view for entries without a dedicated editor. */
function ItemPreview({ item }: { item: LibraryItem }) {
  const detail = useQuery({ queryKey: ['library-item', item.kind, item.name], queryFn: () => api.libraryItem(item.kind, item.name) })
  const version = item.versions.at(-1)?.number
  const root = detail.data?.root
  const source = useQuery({
    queryKey: ['library-source', item.kind, item.name, version, root],
    queryFn: () => api.libraryFile(item.kind, item.name, version!, root!),
    enabled: version != null && !!root,
  })
  return <>
    <h2>{item.title}</h2>
    {item.description && <p className="hint">{item.description}</p>}
    <span className="eyebrow">{root ?? 'Configuration'}</span>
    <ErrorBox error={detail.error ?? source.error} />
    {(detail.isLoading || source.isLoading) && <Loading what="configuration" />}
    {source.data && <pre className="code code-wrap">{source.data}</pre>}
    <p className="hint">{item.kind === 'templates' ? 'Choose a template when importing an API definition. Custom template versions are also available through the platform API.' : 'Use this versioned reference in your MCP server definition:'}</p>
    <pre className="code code-wrap">{item.reference}</pre>
  </>
}
