import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { api, type LibraryItem } from '../api'
import { ErrorBox, Loading, PageHead, ago } from '../ui'
import { PolicyEditor } from './PolicyEditor'

const KINDS: [string, string, string][] = [
  ['contracts', 'API contracts', 'OpenAPI and Swagger documents several servers ship.'],
  ['naming', 'Naming policies', 'How operation ids become tool names.'],
  ['rules', 'Rule sets', 'Descriptions, safety and selection rules.'],
  ['packs', 'Pack configs', 'Settings for the extra tools in packages.'],
  ['templates', 'Templates', 'Definitions others extend.'],
]

export function LibraryPage() {
  const library = useQuery({ queryKey: ['library'], queryFn: api.library })
  const items = library.data?.items ?? []
  const ws = library.data?.workspace
  const [params, setParams] = useSearchParams()
  const open = items.find((i) => i.kind === 'naming' && i.name === params.get('naming'))
  return (
    <>
      <PageHead title="Shared library"
        sub={<>Defined once, used by several servers. Definitions reference items as <span className="mono">mcpgp://{ws ?? '…'}/&lt;kind&gt;/&lt;name&gt;@&lt;version&gt;</span>.</>} />
      <div className="body">
        <section className="col col-wide" aria-label="Library">
          <ErrorBox error={library.error} />
          {library.isLoading && <Loading what="the library" />}
          {KINDS.map(([kind, title, hint]) => {
            const ofKind = items.filter((i) => i.kind === kind)
            return (
              <div key={kind} id={kind} className="card">
                <div className="card-head"><h2>{title}</h2><span className="tag">{ofKind.length}</span></div>
                <p className="hint">{hint}</p>
                {ofKind.map((item) => <Item key={item.name} item={item} open={item.kind === 'naming' ? () => setParams({ naming: item.name }) : undefined}
                  selected={item === open} />)}
              </div>
            )
          })}
        </section>
        <aside className="stage stage-narrow" aria-label={open ? 'Naming policy' : 'How to add'}>
          {open ? <>
            <button type="button" className="btn btn-sm btn-stage" style={{ alignSelf: 'flex-start' }} onClick={() => setParams({})}>Close</button>
            <PolicyEditor key={open.name} item={open} />
          </> : <>
          <h2>Adding to the library</h2>
          <p className="hint">Items are added and versioned through the API (and the CLI), so pipelines own them:</p>
          <pre className="code code-wrap">{`POST /api/v1/library/naming?name=company-standard
{ "files": { "company-standard.yaml": "…" } }

POST /api/v1/library/naming/company-standard/versions`}</pre>
          <p className="hint">A new version of an item recomputes the previews of every server that references it with <span className="mono">@latest</span>.
            Naming policies can also be changed here: open one.</p>
          </>}
        </aside>
      </div>
    </>
  )
}

function Item({ item, open, selected }: { item: LibraryItem; open?: () => void; selected?: boolean }) {
  const latest = item.versions.at(-1)
  return (
    <div className="row" style={selected ? { background: 'var(--accent-soft)' } : undefined}>
      <span style={{ minWidth: 0 }}>
        <b>{item.title}</b><br />
        <span className="meta mono" style={{ overflowWrap: 'anywhere' }}>{item.reference}</span>
      </span>
      <span className="actions">
        <span className="meta">{item.versions.length} version{item.versions.length === 1 ? '' : 's'} · {latest ? `${latest.author} ${ago(latest.created)}` : ''}</span>
        {open && <button type="button" className="btn btn-sm" onClick={open}>Open</button>}
      </span>
    </div>
  )
}
