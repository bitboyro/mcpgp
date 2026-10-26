import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Change, type LibraryItem } from '../api'
import { diffLines } from '../choices'
import { METHOD_VERBS } from '../naming'
import { escape, validWord, wordsKey } from '../namingEdits'
import { useSession } from '../session'
import { ErrorBox, Loading, Icon } from '../ui'
import { WordField } from './Naming'

interface Pending {
  label: string
  change: Change
}

/**
 * A shared naming policy on the platform (spec/platform-api.md §2.3): its verbs from HTTP methods and
 * its vocabulary, edited and saved as the policy's next version. Every server that extends the policy
 * at its latest version picks the change up; servers that pin a version keep theirs until they move.
 */
export function PolicyEditor({ item }: { item: LibraryItem }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const detail = useQuery({ queryKey: ['library', item.kind, item.name], queryFn: () => api.libraryItem(item.kind, item.name) })
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [words, setWords] = useState('')
  const [becomes, setBecomes] = useState('')
  const [message, setMessage] = useState('')
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
  const rootPath = detail.data?.root
  const root = useQuery({ queryKey: ['library-root', item.name, base, rootPath], queryFn: () => api.libraryFile(item.kind, item.name, base, rootPath!),
    enabled: base > 0 && !!rootPath && changes.length > 0 })
  const save = useMutation({
    mutationFn: () => api.saveLibraryEdit(item.kind, item.name, base, changes, message || summary.slice(0, 200)),
    onSuccess: async () => {
      setPending({})
      setMessage('')
      for (const key of ['library', 'naming', 'manifest', 'operations']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const editable = can('library:write')
  const put = (label: string, change: Change, unchanged = false) => {
    setPending((now) => {
      const next = { ...now }
      if (unchanged) delete next[change.path]
      else next[change.path] = { label, change }
      return next
    })
    save.reset()
  }

  if (detail.isLoading) return <Loading what="the policy" />
  if (!detail.data) return <ErrorBox error={detail.error} />
  const policy = detail.data.policy ?? {}
  const vocab = Object.entries(policy.vocabulary ?? {})
  const users = detail.data.usedBy ?? []
  const pinned = users.filter((u) => u.pinned !== null && u.pinned !== String(base))
  const problems = preview.data?.problems ?? []
  const busy = changes.length > 0 && preview.isFetching
  const key = wordsKey(words)

  return (
    <>
      <div>
        <div className="eyebrow">Naming policy · version {base}</div>
        <h2>{detail.data.title}</h2>
        <span className="meta mono">{detail.data.reference}</span>
      </div>
      <p className="hint">Used by {users.length === 0 ? 'no server yet' : users.map((u, i) => (
        <span key={u.server}>{i > 0 && ', '}<Link to={`/servers/${u.server}/tools?view=naming`}>{u.server}</Link>{u.pinned !== null && ` (pins @${u.pinned})`}</span>
      ))}.</p>
      {pinned.length > 0 && (
        <div className="note">A new version reaches servers that extend the policy without a version (or <span className="mono">@latest</span>).
          {' '}{pinned.map((u) => u.server).join(', ')} pin{pinned.length === 1 ? 's' : ''} an older version and keep{pinned.length === 1 ? 's' : ''} it until
          their definition moves to the new one.</div>
      )}

      <h3>Verbs from HTTP methods</h3>
      <p className="hint">For operations without an operationId.</p>
      <div className="filters">
        {METHOD_VERBS.map(([method, fallback, label]) => {
          const path = `/methodVerbs/${method}`
          const current = policy.methodVerbs?.[method] ?? fallback
          return <WordField key={method + (pending[path] ? '*' : '')} label={label} current={current} own={false} disabled={!editable}
            onPick={(w) => put(`${label} → ${w}`, { op: 'set', path, value: w }, w === current)} />
        })}
      </div>

      <h3>Vocabulary</h3>
      <table className="table table-stage" aria-label="Policy vocabulary">
        <thead><tr><th>Words</th><th>Become</th><th /></tr></thead>
        <tbody>
          {vocab.map(([k, v]) => {
            const path = `/vocabulary/${escape(k)}`
            const change = pending[path]?.change
            return (
              <tr key={k} style={change?.op === 'remove' ? { textDecoration: 'line-through' } : undefined}>
                <td className="mono">{k.split('|').join(', ')}</td>
                <td className="mono">{change?.op === 'set' ? String(change.value) : v}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {editable && !change && <>
                    <button type="button" className="btn btn-sm btn-stage" onClick={() => { setWords(k.split('|').join(', ')); setBecomes(String(v)) }}>Change</button>{' '}
                    <button type="button" className="btn btn-sm btn-stage" onClick={() => put(`remove ${k.split('|').join(', ')}`, { op: 'remove', path })}><Icon name="trash" />Remove</button>
                  </>}
                  {change && <button type="button" className="btn btn-sm btn-stage" onClick={() => put('', change, true)}>Undo</button>}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      {editable && (
        <form className="filters" aria-label="Add or change a policy vocabulary entry" onSubmit={(e) => {
          e.preventDefault()
          if (!key || !validWord(becomes)) return
          put(`${key.split('|').join(', ')} → ${becomes}`, { op: 'set', path: `/vocabulary/${escape(key)}`, value: becomes })
          setWords('')
          setBecomes('')
        }}>
          <label className="field" style={{ flex: '2 1 160px' }}>Words
            <input className="input input-stage" value={words} placeholder="retrieve, fetch" onChange={(e) => setWords(e.target.value)} />
          </label>
          <label className="field" style={{ flex: '1 1 100px' }}>Become
            <input className="input input-stage" value={becomes} placeholder="get" onChange={(e) => setBecomes(e.target.value.trim().toLowerCase())} />
          </label>
          <button type="submit" className="btn btn-stage" style={{ alignSelf: 'flex-end' }} disabled={!key || !validWord(becomes)}>Add</button>
        </form>
      )}

      {changes.length > 0 && (
        <form className="list" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
          <h3>{changes.length} unsaved change{changes.length === 1 ? '' : 's'}</h3>
          {problems.length > 0 && <div className="note note-bad">{problems.join('; ')}</div>}
          {preview.data && root.data && (
            <pre className="code" aria-label="Changes to the policy">
              {diffLines(root.data, preview.data.text).map((l, i) => (
                <span key={i} style={{ display: 'block', color: l.kind === '+' ? 'var(--note-ok-ink)' : l.kind === '-' ? 'var(--note-bad-ink)' : undefined }}>
                  {l.kind === '…' ? '…' : `${l.kind} ${l.text}`}
                </span>
              ))}
            </pre>
          )}
          <label className="field">What changed (optional)
            <input className="input input-stage" value={message} maxLength={200} placeholder={summary} onChange={(e) => setMessage(e.target.value)} />
          </label>
          <ErrorBox error={save.error ?? preview.error} />
          {save.error instanceof ApiError && save.error.status === 409 && <p className="hint">Someone saved a newer version; discard and reload.</p>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={busy || problems.length > 0 || save.isPending}>Save as version {base + 1}</button>
            <button type="button" className="btn btn-stage" onClick={() => { setPending({}); save.reset() }}>Discard</button>
          </div>
          <p className="hint">Each server's Naming view shows which tool names a new policy version changes; names in a server's lock file stay.</p>
        </form>
      )}
    </>
  )
}
