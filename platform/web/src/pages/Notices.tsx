import { useState } from 'react'
import type { Change, DefinitionData, Manifest, Notice } from '../api'
import { describeScope, noticeId, noticePath, removeNotice, scopeMatch, scopeOf, setNotice, type Scope } from '../descriptions'
import { Segmented } from '../ui'

interface Props {
  manifest: Manifest
  definition: DefinitionData | undefined
  editable: boolean
  pending: Record<string, { label: string; change: Change }>
  pick: (key: string, label: string, original: boolean, change: Change) => void
}

/**
 * Notices on Overview (definition-format.md §5.1): text added to the descriptions of every tool, or of
 * the tools it selects (a tag, the tools that change data, chosen tools). Never cut off; changes are
 * previewed and saved like the other settings on the page.
 */
export function NoticesCard({ manifest, definition, editable, pending, pick }: Props) {
  const [editing, setEditing] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [position, setPosition] = useState<'append' | 'prepend'>('append')
  const [scope, setScope] = useState<Scope>('all')
  const [tag, setTag] = useState('')
  const [chosen, setChosen] = useState<string[]>([])

  const own = new Set((definition?.root.descriptions?.notices ?? []).map((n) => n.id))
  const notices: Notice[] = [...(definition?.resolved.descriptions?.notices ?? [])]
  for (const p of Object.values(pending)) {
    const id = /\[id=([^\]]+)]$/.exec(p.change.path)?.[1]
    if (!p.change.path.startsWith('/descriptions/notices/') || !id) continue
    const at = notices.findIndex((n) => n.id === id)
    if (p.change.op === 'remove') {
      if (at >= 0) notices.splice(at, 1)
    } else {
      const next = { id, ...(p.change.value as Omit<Notice, 'id'>) }
      if (at >= 0) notices[at] = next
      else notices.push(next)
    }
  }
  const tags = [...new Set(manifest.tools.flatMap((t) => t.operation?.tags ?? []))].sort()
  const names = manifest.tools.map((t) => t.name).sort()

  const open = (notice?: Notice) => {
    const s = scopeOf(notice?.match)
    setEditing(notice?.id ?? 'new')
    setText(notice?.text ?? '')
    setPosition(notice?.position ?? 'append')
    setScope(s.scope)
    setTag(s.tag || tags[0] || '')
    setChosen(s.tools)
  }
  const valid = text.trim().length > 0 && text.length <= 500 && (scope !== 'tag' || !!tag) && (scope !== 'tools' || chosen.length > 0)
  const save = () => {
    const id = editing === 'new' || !editing ? noticeId(text, notices.map((n) => n.id)) : editing
    const notice: Notice = { id, text: text.trim(), position, match: scopeMatch(scope, tag, chosen) }
    pick(noticePath(id), `notice “${notice.text.slice(0, 40)}${notice.text.length > 40 ? '…' : ''}” on ${describeScope(notice.match)}`, false, setNotice(notice))
    setEditing(null)
  }

  return (
    <div className="card">
      <div className="card-head">
        <h2>Notices on tool descriptions</h2>
        {editable && editing === null && <button type="button" className="btn btn-sm" onClick={() => open()}>Add notice</button>}
      </div>
      <p className="hint">Company policy, disclaimers: text added to the descriptions of every tool or of chosen tools. Never cut off;
        a long description is shortened to make room.</p>
      {notices.length === 0 && editing === null && <p className="empty">No notices.</p>}
      <div className="rows">
        {notices.map((n) => (
          <div key={n.id} className="row" style={{ background: 'none', padding: '10px 0', alignItems: 'flex-start' }}>
            <span style={{ flex: 1, minWidth: 0 }}>
              <span style={{ overflowWrap: 'anywhere' }}>{n.text}</span><br />
              <span className="hint">{n.position === 'prepend' ? 'before' : 'after'} the description of {describeScope(n.match)}</span>
              {pending[noticePath(n.id)] && <> <span className="tag">changed</span></>}
              {!own.has(n.id) && !pending[noticePath(n.id)] && <> <span className="tag">inherited</span></>}
            </span>
            {editable && editing === null && (own.has(n.id) || pending[noticePath(n.id)]) && (
              <span className="actions">
                <button type="button" className="btn btn-sm" onClick={() => open(n)}>Edit</button>
                <button type="button" className="btn btn-sm" onClick={() => pick(noticePath(n.id), `remove notice “${n.text.slice(0, 40)}”`, false, removeNotice(n.id))}>Remove</button>
              </span>
            )}
          </div>
        ))}
      </div>
      {editing !== null && (
        <form className="list" aria-label="Notice" onSubmit={(e) => { e.preventDefault(); if (valid) save() }}>
          <label className="field">Text
            <textarea className="input" rows={3} value={text} maxLength={500} style={{ padding: 10 }} onChange={(e) => setText(e.target.value)}
              placeholder="Company policy: customer data stays in the EU." />
          </label>
          <Segmented label="Where" value={position} onChange={setPosition} options={[['append', 'After the description'], ['prepend', 'Before it']]} />
          <Segmented label="Applies to" value={scope} onChange={setScope}
            options={[['all', 'Every tool'], ['tag', 'A tag'], ['writes', 'Tools that change data'], ['tools', 'Chosen tools']]} />
          {scope === 'tag' && (
            <label className="field">Tag
              <select className="input" value={tag} onChange={(e) => setTag(e.target.value)}>
                {tags.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </label>
          )}
          {scope === 'tools' && (
            <fieldset className="list" style={{ border: 0, padding: 0, margin: 0, maxHeight: 220, overflow: 'auto' }}>
              <legend className="hint">Tools</legend>
              {names.map((name) => (
                <label key={name} className="mono" style={{ fontSize: 13, display: 'flex', gap: 8, alignItems: 'center' }}>
                  <input type="checkbox" checked={chosen.includes(name)}
                    onChange={(e) => setChosen((now) => (e.target.checked ? [...now, name] : now.filter((x) => x !== name)))} />{name}
                </label>
              ))}
            </fieldset>
          )}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={!valid}>{editing === 'new' ? 'Add' : 'Change'}</button>
            <button type="button" className="btn" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  )
}
