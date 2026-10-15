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
  ['templates', 'Templates', 'Definitions others extend.'],
]

export function LibraryPage() {
  const library = useQuery({ queryKey: ['library'], queryFn: api.library })
  const items = library.data?.items ?? []
  const ws = library.data?.workspace
  const [params, setParams] = useSearchParams()
  const open = items.find((i) => (i.kind === 'naming' && i.name === params.get('naming')) || (i.kind === 'notices' && i.name === params.get('notices')))
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
                {ofKind.map((item) => <Item key={item.name} item={item}
                  open={item.kind === 'naming' || item.kind === 'notices' ? () => setParams({ [item.kind]: item.name }) : undefined}
                  selected={item === open} />)}
                {kind === 'notices' && <NewNoticeSet onCreated={(name) => setParams({ notices: name })} />}
              </div>
            )
          })}
        </section>
        <aside className="stage stage-narrow" aria-label={open ? (open.kind === 'naming' ? 'Naming policy' : 'Shared notices') : 'How to add'}>
          {open ? <>
            <button type="button" className="btn btn-sm btn-stage" style={{ alignSelf: 'flex-start' }} onClick={() => setParams({})}>Close</button>
            {open.kind === 'naming' ? <PolicyEditor key={open.name} item={open} /> : <NoticeSetEditor key={open.name} item={open} />}
          </> : <>
          <h2>Adding to the library</h2>
          <p className="hint">Items are added and versioned through the API (and the CLI), so pipelines own them:</p>
          <pre className="code code-wrap">{`POST /api/v1/library/naming?name=company-standard
{ "files": { "company-standard.yaml": "…" } }

POST /api/v1/library/naming/company-standard/versions`}</pre>
          <p className="hint">A new version of an item recomputes the previews of every server that references it with <span className="mono">@latest</span>.
            Naming policies and shared notices can also be changed here: open one.</p>
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
