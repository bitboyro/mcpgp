import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Change, type LibraryItem, type Notice } from '../api'
import { describeScope, noticeId, scopeMatch, scopeOf, type Scope } from '../descriptions'
import { setNoticePath, setSetNotice } from '../sharedNotices'
import { useSession } from '../session'
import { ErrorBox, Loading, Segmented, Icon } from '../ui'

interface Pending {
  label: string
  change: Change
}

/**
 * Shared notices on the Library page (spec/platform-api.md §2.3): the set's notices, edited and saved as
 * its next version, the servers that include it, and adding it to servers (or moving them to the latest
 * version) in one step. Each changed server gets its next version.
 */
export function NoticeSetEditor({ item }: { item: LibraryItem }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const detail = useQuery({ queryKey: ['library', item.kind, item.name], queryFn: () => api.libraryItem(item.kind, item.name) })
  const servers = useQuery({ queryKey: ['servers'], queryFn: () => api.servers() })
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [message, setMessage] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [position, setPosition] = useState<'append' | 'prepend'>('append')
  const [scope, setScope] = useState<Scope>('all')
  const [tag, setTag] = useState('')
  const [tools, setTools] = useState('')
  const [chosen, setChosen] = useState<string[] | null>(null)

  const base = detail.data?.versions.at(-1)?.number ?? 0
  const changes = Object.values(pending).map((p) => p.change)
  const summary = Object.values(pending).map((p) => p.label).join('; ')
  const preview = useQuery({
    queryKey: ['library-edit', item.kind, item.name, base, changes],
    queryFn: () => api.previewLibraryEdit(item.kind, item.name, base, changes),
    enabled: changes.length > 0 && base > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const refresh = async () => {
    for (const key of ['library', 'servers', 'server', 'manifest', 'definition', 'operations']) await queryClient.invalidateQueries({ queryKey: [key] })
  }
  const save = useMutation({
    mutationFn: () => api.saveLibraryEdit(item.kind, item.name, base, changes, message || summary.slice(0, 200)),
    onSuccess: async () => { setPending({}); setMessage(''); await refresh() },
  })
  const apply = useMutation({
    mutationFn: (targets: string[]) => api.applyNotices(item.name, { servers: targets, version: base }),
    onSuccess: refresh,
  })

  if (detail.isLoading) return <Loading what="the notices" />
  if (!detail.data) return <ErrorBox error={detail.error} />

  const editable = can('library:write')
  const canApply = can('servers:write')
  const notices: Notice[] = [...(detail.data.set?.notices ?? [])]
  for (const p of Object.values(pending)) {
    const id = /\[id=([^\]]+)]$/.exec(p.change.path)?.[1]
    if (!id) continue
    const at = notices.findIndex((n) => n.id === id)
    if (p.change.op === 'remove') {
      if (at >= 0) notices.splice(at, 1)
    } else {
      const next = { id, ...(p.change.value as Omit<Notice, 'id'>) }
      if (at >= 0) notices[at] = next
      else notices.push(next)
    }
  }
  const users = detail.data.usedBy ?? []
  const using = new Map(users.map((u) => [u.server, u.pinned]))
  const active = (servers.data?.items ?? []).filter((s) => !s.archived && s.latestVersion !== null)
  // servers not on the latest version yet: not using it, or pinning an older one
  const behind = active.filter((s) => using.get(s.id) !== String(base))
  const targets = chosen ?? behind.map((s) => s.id)
  const problems = preview.data?.problems ?? []

  const put = (label: string, change: Change) => {
    setPending((now) => ({ ...now, [change.path]: { label, change } }))
    save.reset()
  }
  const open = (notice?: Notice) => {
    const s = scopeOf(notice?.match)
    setEditing(notice?.id ?? 'new')
    setText(notice?.text ?? '')
    setPosition(notice?.position ?? 'append')
    setScope(s.scope)
    setTag(s.tag)
    setTools(s.tools.join(', '))
  }
  const toolList = tools.split(/[\s,]+/).filter(Boolean)
  const valid = text.trim().length > 0 && text.length <= 500 && (scope !== 'tag' || !!tag.trim()) && (scope !== 'tools' || toolList.length > 0)
  const done = () => {
    const id = editing === 'new' || !editing ? noticeId(text, notices.map((n) => n.id)) : editing
    const notice: Notice = { id, text: text.trim(), position, match: scopeMatch(scope, tag.trim(), toolList) }
    put(`notice “${notice.text.slice(0, 40)}${notice.text.length > 40 ? '…' : ''}”`, setSetNotice(notice))
    setEditing(null)
  }

  return (
    <>
      <div>
        <div className="eyebrow">Shared notices · version {base}</div>
        <h2>{detail.data.title}</h2>
        <span className="meta mono">{detail.data.reference}</span>
      </div>
      <p className="hint">Text added to the tool descriptions of every server that includes this set; a server's own notice with the same id replaces one.</p>

      <h3>Notices</h3>
      <div className="rows">
        {notices.map((n) => (
          <div key={n.id} className="row" style={{ background: 'none', padding: '8px 0', alignItems: 'flex-start' }}>
            <span style={{ flex: 1, minWidth: 0 }}>
              <span style={{ overflowWrap: 'anywhere' }}>{n.text}</span><br />
              <span className="hint">{n.position === 'prepend' ? 'before' : 'after'} the description of {describeScope(n.match)} · <span className="mono">{n.id}</span></span>
              {pending[setNoticePath(n.id)] && <> <span className="tag">changed</span></>}
            </span>
            {editable && editing === null && (
              <span className="actions">
                <button type="button" className="btn btn-sm btn-stage" onClick={() => open(n)}>Edit</button>
                <button type="button" className="btn btn-sm btn-stage" disabled={notices.length === 1}
                  title={notices.length === 1 ? 'A set keeps at least one notice' : undefined}
                  onClick={() => put(`remove “${n.text.slice(0, 40)}”`, { op: 'remove', path: setNoticePath(n.id) })}>Remove</button>
              </span>
            )}
          </div>
        ))}
      </div>
      {editable && editing === null && <button type="button" className="btn btn-sm btn-stage" style={{ alignSelf: 'flex-start' }} onClick={() => open()}><Icon name="plus" />Add notice</button>}
      {editing !== null && (
        <form className="list" aria-label="Shared notice" onSubmit={(e) => { e.preventDefault(); if (valid) done() }}>
          <label className="field">Text
            <textarea className="input input-stage" rows={3} value={text} maxLength={500} style={{ padding: 10 }} onChange={(e) => setText(e.target.value)}
              placeholder="Company policy: customer data stays in the EU." />
          </label>
          <Segmented label="Where" value={position} onChange={setPosition} options={[['append', 'After the description'], ['prepend', 'Before it']]} />
          <Segmented label="Applies to" value={scope} onChange={setScope}
            options={[['all', 'Every tool'], ['writes', 'Tools that change data'], ['tag', 'A tag'], ['tools', 'Tools by name']]} />
          {scope === 'tag' && (
            <label className="field">Tag (as in the APIs' specs)
              <input className="input input-stage" value={tag} onChange={(e) => setTag(e.target.value)} placeholder="customers" />
            </label>
          )}
          {scope === 'tools' && (
            <label className="field">Tool names, separated by commas (<span className="mono">~regex</span> works too)
              <input className="input input-stage mono" value={tools} onChange={(e) => setTools(e.target.value)} placeholder="orders_order_refund" />
            </label>
          )}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={!valid}>{editing === 'new' ? 'Add' : 'Change'}</button>
            <button type="button" className="btn btn-stage" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </form>
      )}

      {changes.length > 0 && (
        <form className="list" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
          <h3>{changes.length} unsaved change{changes.length === 1 ? '' : 's'}</h3>
          {problems.length > 0 && <div className="note note-bad">{problems.join('; ')}</div>}
          <label className="field">What changed (optional)
            <input className="input input-stage" value={message} maxLength={200} placeholder={summary} onChange={(e) => setMessage(e.target.value)} />
          </label>
          <ErrorBox error={save.error ?? preview.error} />
          {save.error instanceof ApiError && save.error.status === 409 && <p className="hint">Someone saved a newer version; discard and reload.</p>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={(changes.length > 0 && preview.isFetching) || problems.length > 0 || save.isPending}>
              Save as version {base + 1}</button>
            <button type="button" className="btn btn-stage" onClick={() => { setPending({}); save.reset() }}>Discard</button>
          </div>
          <p className="hint">Servers keep the version they include until they move to the new one (below).</p>
        </form>
      )}

      <h3>Servers</h3>
      <p className="hint">Used by {users.length === 0 ? 'no server yet' : users.map((u, i) => (
        <span key={u.server}>{i > 0 && ', '}<Link to={`/servers/${u.server}`}>{u.server}</Link>{u.pinned !== null && ` @${u.pinned}`}</span>
      ))}.</p>
      {canApply && changes.length === 0 && (
        <div className="list" aria-label="Use on servers">
          {active.length === 0 && <p className="empty">No servers yet.</p>}
          {active.map((s) => {
            const on = using.get(s.id)
            return (
              <label key={s.id} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 14 }}>
                <input type="checkbox" checked={targets.includes(s.id)}
                  onChange={(e) => setChosen(e.target.checked ? [...targets, s.id] : targets.filter((x) => x !== s.id))} />
                <span className="mono">{s.id}</span>
                <span className="hint">{on === undefined ? 'doesn’t use it' : on === String(base) ? `on @${base}` : on === null ? 'uses the latest' : `on @${on}`}
                  {s.parent && ` · variant of ${s.parent}`}</span>
              </label>
            )
          })}
          {active.length > 0 && behind.length === 0 && chosen === null && <p className="hint">Every server is on version {base}.</p>}
          {active.length > 0 && (behind.length > 0 || chosen !== null) && (
            <div className="actions">
              <button type="button" className="btn btn-primary" disabled={targets.length === 0 || apply.isPending}
                onClick={() => apply.mutate(targets)}>{apply.isPending ? 'Saving…' : `Use version ${base} on ${targets.length} server${targets.length === 1 ? '' : 's'}`}</button>
            </div>
          )}
          <p className="hint">Each server that changes gets its next version (its <span className="mono">descriptions.include</span>);
            variants of a chosen server get the notices from it.</p>
          <ErrorBox error={apply.error} />
          {apply.data && (
            <ul className="list" aria-label="Results" style={{ margin: 0, paddingLeft: 18 }}>
              {apply.data.results.map((r) => (
                <li key={r.server} className={r.status === 'failed' ? 'note-bad' : undefined}>
                  <span className="mono">{r.server}</span>: {r.status === 'updated' ? `version ${r.version}${r.from ? ` (was ${r.from.replace(/^.*\//, '')})` : ''}`
                    : r.status === 'unchanged' ? 'already on it' : r.status === 'inherited' ? `gets it from ${r.from}` : `not changed: ${r.error}`}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </>
  )
}
