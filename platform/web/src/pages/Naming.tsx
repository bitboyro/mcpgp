import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Change } from '../api'
import { methodVerbs, nameRows, policyLines, unusedLockEntries, vocabulary, type NameRow } from '../naming'
import { apiPrefix, methodVerb, removeWord, validVerb, renames, serverPrefix, serverSuffix, setWord, switchOff, validPrefix, validWord, vocabularyPath, wordsKey } from '../namingEdits'
import { useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, Segmented } from '../ui'
import { Checks } from './Overview'

type Show = 'all' | 'drift' | 'unlocked'

const HOW: Record<NameRow['how'], string> = {
  pipeline: 'from the policy',
  rule: 'set by a rule',
  lock: 'kept by the lock file',
  drift: 'kept by the lock file; the policy now gives another name',
}

interface Pending {
  label: string
  change: Change
}

const keyOf = (words: string) => words.split(', ').join('|')

/**
 * The naming of a server (naming.md): the policy it follows, in words, its vocabulary and prefixes
 * (editable: changes are previewed and saved as the next version), and every tool's name with how it
 * came about, including names the lock file keeps although the policy would change them.
 */
export function NamingView({ id }: { id: string }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const server = useServer(id)
  const manifest = useManifest(id)
  const naming = useQuery({ queryKey: ['naming', id], queryFn: () => api.naming(id), retry: false })
  const [show, setShow] = useState<Show>('all')
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [words, setWords] = useState('')
  const [becomes, setBecomes] = useState('')
  const [message, setMessage] = useState('')
  const base = server.data?.latestVersion ?? 0
  const editable = can('servers:write') && !!naming.data && !!manifest.data && !server.data?.archived
  const changes = Object.values(pending).map((p) => p.change)
  const summary = Object.values(pending).map((p) => p.label).join('; ')
  const preview = useQuery({
    queryKey: ['edit-preview', id, base, changes],
    queryFn: () => api.previewEdit(id, base, changes),
    enabled: changes.length > 0 && base > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const broken = changes.length > 0 && preview.data && !preview.data.manifest
  const busy = changes.length > 0 && preview.isFetching
  const shownManifest = changes.length > 0 && preview.data?.manifest ? preview.data.manifest : manifest.data
  const renamed = useMemo(() => (changes.length > 0 && manifest.data && preview.data?.manifest ? renames(manifest.data, preview.data.manifest) : []),
    [changes.length, manifest.data, preview.data])
  const save = useMutation({
    mutationFn: () => api.saveEdit(id, base, changes, message || ('Naming: ' + summary).slice(0, 200)),
    onSuccess: async () => {
      setPending({})
      setMessage('')
      for (const key of ['server', 'manifest', 'servers', 'naming', 'operations', 'version']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })

  const put = (label: string, change: Change, unchanged = false) => {
    setPending((now) => {
      const next = { ...now }
      if (unchanged) delete next[change.path]
      else next[change.path] = { label, change }
      return next
    })
    save.reset()
  }

  const rows = useMemo(() => (shownManifest ? nameRows(shownManifest) : []), [shownManifest])
  const drift = rows.filter((r) => r.how === 'drift')
  const unlocked = rows.filter((r) => r.how === 'pipeline' || r.how === 'rule')
  const shownRows = show === 'drift' ? drift : show === 'unlocked' ? unlocked : rows
  const unused = naming.data ? unusedLockEntries(naming.data, rows) : []

  const vocab = naming.data ? vocabulary(naming.data) : []
  const known = new Set(vocab.map((v) => vocabularyPath(keyOf(v.words))))
  const added = Object.values(pending).filter((p) => p.change.path.startsWith('/naming/vocabulary/') && !known.has(p.change.path))
  const newKey = wordsKey(words)
  const inPolicy = (key: string) => vocab.some((v) => keyOf(v.words) === key && !v.own)
  const addOk = !!newKey && validWord(becomes)

  return (
    <div className="body">
      <section className="col" aria-label="Naming policy">
        <ErrorBox error={naming.error ?? manifest.error} />
        {naming.isLoading && <Loading what="the naming policy" />}
        {changes.length > 0 && (
          <form className="card" style={{ borderColor: 'var(--accent)' }} onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
            <div className="card-head">
              <h2>{changes.length} unsaved change{changes.length === 1 ? '' : 's'}</h2>
              <span className="hint">{busy ? 'Updating the preview…' : ''}</span>
            </div>
            <ul className="hint" style={{ margin: 0, paddingLeft: 18 }}>{Object.values(pending).map((p) => <li key={p.change.path}>{p.label}</li>)}</ul>
            <label className="field">What changed (optional)
              <input className="input" value={message} maxLength={200} placeholder={'Naming: ' + summary} onChange={(e) => setMessage(e.target.value)} />
            </label>
            <ErrorBox error={save.error ?? preview.error} />
            {save.error instanceof ApiError && save.error.status === 409 && (
              <p className="hint">Someone saved a newer version. <button type="button" className="btn btn-sm"
                onClick={() => { setPending({}); save.reset(); void queryClient.invalidateQueries({ queryKey: ['server', id] }) }}>Reload</button></p>
            )}
            <div className="actions">
              <button type="submit" className="btn btn-primary" disabled={!!broken || busy || save.isPending}>Save as version {base + 1}</button>
              <button type="button" className="btn" onClick={() => { setPending({}); save.reset() }}>Discard</button>
            </div>
          </form>
        )}
        {naming.data && (
          <>
            <div className="card">
              <div className="card-head">
                <h2>Policy</h2>
                {naming.data.extends ? <span className="mono hint">{naming.data.extends}</span> : <span className="hint">the server's own</span>}
              </div>
              {naming.data.extends && (
                <p className="hint">Shared from the <Link to="/library">library</Link>; lines marked <span className="tag">this server</span> are
                  set in the server's definition on top of it.</p>
              )}
              <dl className="kv">
                {policyLines(naming.data).map((l) => (
                  <div key={l.label} style={{ display: 'contents' }}>
                    <dt>{l.label}</dt>
                    <dd>{l.value}{l.own && naming.data.extends && <> <span className="tag">this server</span></>}</dd>
                  </div>
                ))}
              </dl>
            </div>
            {manifest.data && (
              <div className="card">
                <h2>Prefixes and suffix</h2>
                <p className="hint">Tool names start with the prefixes, server prefix first, and end with the suffix: <span className="mono">
                  {[naming.data.effective.namespace?.server, manifest.data.apis[0]?.toolPrefix ?? manifest.data.apis[0]?.id, 'customer_list',
                    naming.data.effective.namespace?.suffix].filter(Boolean).join('_')}</span>. One tool's name changes in its editor on Tools &amp; rules.</p>
                {manifest.data.apis.map((a) => {
                  const current = a.toolPrefix ?? a.id
                  return (
                    <PrefixField key={a.id + (pending[apiPrefix(a.id, '').path] ? '*' : '')} label={manifest.data!.apis.length > 1 ? `Prefix for ${a.title ?? a.id}` : 'API prefix'} current={current}
                      disabled={!editable} required onPick={(v) => put(`${a.id} prefix ${current} → ${v}`, apiPrefix(a.id, v), v === current)} />
                  )
                })}
                <PrefixField key={'server' + (pending['/naming/namespace/server'] ? '*' : '')} label="Server prefix" current={naming.data.effective.namespace?.server ?? ''} disabled={!editable}
                  onPick={(v) => put(v ? `server prefix ${v}` : 'no server prefix', serverPrefix(v), v === (naming.data!.effective.namespace?.server ?? ''))} />
                <PrefixField key={'suffix' + (pending['/naming/namespace/suffix'] ? '*' : '')} label="Suffix for every tool" current={naming.data.effective.namespace?.suffix ?? ''} disabled={!editable}
                  onPick={(v) => put(v ? `suffix ${v}` : 'no suffix', serverSuffix(v), v === (naming.data!.effective.namespace?.suffix ?? ''))} />
              </div>
            )}
            <div className="card">
              <h2>Verbs from HTTP methods</h2>
              <p className="hint">Used when an operation has no operationId: the name is the path's nouns and this verb
                (<span className="mono">PUT /customers/{'{id}'}</span> → <span className="mono">customer_{methodVerbs(naming.data).find((v) => v.method === 'PUT')!.verb}</span>).</p>
              <div className="filters">
                {methodVerbs(naming.data).map((v) => (
                  <WordField key={v.method + (pending[methodVerb(v.method, '').path] ? '*' : '')} label={v.label} current={v.verb} own={v.own && !!naming.data!.extends}
                    disabled={!editable} onPick={(w) => put(`${v.label} → ${w}`, methodVerb(v.method, w), w === v.verb)} />
                ))}
              </div>
            </div>
            <div className="card">
              <h2>Vocabulary</h2>
              <p className="hint">Words in names that become other words, after the name is shaped.</p>
              {vocab.length + added.length === 0 ? <p className="empty">No vocabulary.</p> : (
                <table className="table" aria-label="Vocabulary">
                  <thead><tr><th>Words</th><th>Become</th><th /></tr></thead>
                  <tbody>
                    {vocab.map((v) => {
                      const key = keyOf(v.words)
                      const change = pending[vocabularyPath(key)]?.change
                      const gone = change && (change.op === 'remove' || change.value === null)
                      return (
                        <tr key={key} style={gone ? { textDecoration: 'line-through', color: 'var(--muted)' } : undefined}>
                          <td className="mono">{v.words}</td>
                          <td className="mono">{change?.op === 'set' && change.value !== null ? String(change.value) : v.becomes}</td>
                          <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                            {v.own && naming.data!.extends && <span className="tag">this server</span>}
                            {change && <> <span className="tag">changed</span></>}
                            {editable && !change && (
                              <>
                                {' '}<button type="button" className="btn btn-sm" onClick={() => { setWords(v.words); setBecomes(v.becomes) }}>Change</button>
                                {' '}{v.own
                                  ? <button type="button" className="btn btn-sm" onClick={() => put(`remove ${v.words}`, removeWord(key))}>Remove</button>
                                  : <button type="button" className="btn btn-sm" onClick={() => put(`switch off ${v.words}`, switchOff(key))}>Switch off</button>}
                              </>
                            )}
                            {editable && change && <> <button type="button" className="btn btn-sm" onClick={() => put('', change, true)}>Undo</button></>}
                          </td>
                        </tr>
                      )
                    })}
                    {added.map((p) => (
                      <tr key={p.change.path}>
                        <td className="mono">{p.change.path.slice('/naming/vocabulary/'.length).replace(/~1/g, '/').replace(/~0/g, '~').split('|').join(', ')}</td>
                        <td className="mono">{String(p.change.value)}</td>
                        <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}><span className="tag">new</span>{' '}
                          <button type="button" className="btn btn-sm" onClick={() => put('', p.change, true)}>Undo</button></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {editable && (
                <form className="filters" aria-label="Add or change a vocabulary entry" onSubmit={(e) => {
                  e.preventDefault()
                  if (!newKey || !addOk) return
                  put(`${newKey.split('|').join(', ')} → ${becomes}${inPolicy(newKey) ? ' (over the policy)' : ''}`, setWord(newKey, becomes))
                  setWords('')
                  setBecomes('')
                }}>
                  <label className="field" style={{ flex: '2 1 180px' }}>Words
                    <input className="input" value={words} placeholder="retrieve, fetch" onChange={(e) => setWords(e.target.value)} />
                  </label>
                  <label className="field" style={{ flex: '1 1 120px' }}>Become
                    <input className="input" value={becomes} placeholder="get" onChange={(e) => setBecomes(e.target.value.trim().toLowerCase())} />
                  </label>
                  <button type="submit" className="btn" style={{ alignSelf: 'flex-end' }} disabled={!addOk}>Add</button>
                </form>
              )}
              {words && !newKey && <p className="hint">Words are lowercase letters, digits and _; separate several with commas.</p>}
            </div>
            <p className="hint">A rule can add a prefix or suffix to chosen tools only (<span className="mono">name: {'{'} append: _v2 {'}'}</span>), in the
              definition's files on <Link to={`/servers/${id}/sources`}>Sources</Link>. Shared policies change in the library.</p>
          </>
        )}
      </section>
      <section className="stage" aria-label="Names">
        {changes.length > 0 && (
          <>
            <div className="card-head">
              <div>
                <div className="eyebrow">With your changes</div>
                <h2>{renamed.filter((r) => !r.kept).length} names change{renamed.some((r) => r.kept) ? `, ${renamed.filter((r) => r.kept).length} kept by the lock file` : ''}</h2>
              </div>
              <span className="live">{busy ? 'Updating…' : 'Preview'}</span>
            </div>
            {broken && <><div className="note note-bad">With these changes the definition doesn't compile.</div><Checks diagnostics={preview.data?.diagnostics ?? []} /></>}
            {renamed.some((r) => !r.kept) && (
              <div className="note note-warn">Renaming a tool breaks agents, prompts and clients that call it by its old name.</div>
            )}
            {renamed.length > 0 && (
              <table className="table table-stage" aria-label="Name changes">
                <thead><tr><th>Operation</th><th>Now</th><th>After</th></tr></thead>
                <tbody>
                  {renamed.map((r) => (
                    <tr key={r.operation + r.before}><td className="mono">{r.operation}</td><td className="mono">{r.before}</td>
                      <td className="mono">{r.kept ? <>{r.before}<br /><span className="meta">the policy would give {r.after}; the lock file keeps the name</span></> : r.after}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
            {!busy && preview.data?.manifest && renamed.length === 0 && <p className="hint">No tool name changes.</p>}
          </>
        )}
        <div className="card-head">
          <div>
            <div className="eyebrow">Names</div>
            <h2>{rows.length} tool names{drift.length > 0 ? `, ${drift.length} kept from the lock file` : ''}</h2>
          </div>
          <Segmented role="tab" label="Show names" value={show} onChange={setShow}
            options={[['all', 'All'], ['drift', `Would change (${drift.length})`], ['unlocked', `Not locked (${unlocked.length})`]]} />
        </div>
        {manifest.isLoading && <Loading what="names" />}
        {naming.data && !naming.data.lock.present && (
          <div className="note note-warn">No <span className="mono">mcp-names.lock</span> yet: names may change when the policy, the
            rules or the contract change. Lock them with <span className="mono">mcpgp names accept --all</span> and commit the file
            with the definition.</div>
        )}
        {drift.length > 0 && show !== 'unlocked' && (
          <div className="note">The lock file keeps these names so agents and prompts that use them keep working. To take the new
            names, accept them with <span className="mono">mcpgp names accept</span>, which is a breaking change for clients.</div>
        )}
        <table className="table table-stage" aria-label="Tool names">
          <thead><tr><th>Operation</th><th>Name</th><th>How</th></tr></thead>
          <tbody>
            {shownRows.map((r) => (
              <tr key={r.name}>
                <td className="mono">{r.id.replace(/^[^:]+:/, '').replace(':', ' ')}</td>
                <td className="mono">{r.name}{r.how === 'drift' && <><br /><span className="meta">now: {r.computed}</span></>}</td>
                <td>{HOW[r.how]}{r.since && <span className="meta"> · since {r.since}</span>}
                  {r.rule && <><br /><span className="meta mono">{r.rule}</span></>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {shownRows.length === 0 && <p className="empty">No names here.</p>}
        {unused.length > 0 && show === 'all' && (
          <>
            <h3>In the lock file, not in this build</h3>
            <p className="hint">The operation is gone or left out; if it comes back, it gets its old name again.</p>
            <ul className="mono" style={{ fontSize: 13, margin: 0, paddingLeft: 18 }}>
              {unused.map((e) => <li key={e.id}>{e.name} <span className="meta">({e.id.replace(/^[^:]+:/, '').replace(':', ' ')})</span></li>)}
            </ul>
          </>
        )}
      </section>
    </div>
  )
}

/** A prefix input that reports a valid value when it leaves the field (or on Enter). */
function PrefixField({ label, current, disabled, required, onPick }: {
  label: string
  current: string
  disabled: boolean
  required?: boolean
  onPick: (value: string) => void
}) {
  const [value, setValue] = useState(current)
  const ok = (!required && value === '') || validPrefix(value)
  const pick = () => { if (ok) onPick(value) }
  return (
    <label className="field">{label}
      <input className="input mono" value={value} disabled={disabled} aria-invalid={!ok}
        onChange={(e) => setValue(e.target.value.trim().toLowerCase())} onBlur={pick}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); pick() } }} />
      {!ok && <span className="hint">Lowercase letters, digits and _, starting with a letter (at most 32).</span>}
    </label>
  )
}

export { WordField }

/** One method's verb: a short input that reports a valid word when it leaves the field (or on Enter). */
function WordField({ label, current, own, disabled, onPick }: {
  label: string
  current: string
  own: boolean
  disabled: boolean
  onPick: (value: string) => void
}) {
  const [value, setValue] = useState(current)
  const ok = validVerb(value)
  const pick = () => { if (ok) onPick(value) }
  return (
    <label className="field" style={{ flex: '1 1 140px' }}><span>{label}{own && <> <span className="tag">this server</span></>}</span>
      <input className="input mono" value={value} disabled={disabled} aria-invalid={!ok}
        onChange={(e) => setValue(e.target.value.trim().toLowerCase())} onBlur={pick}
        onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); pick() } }} />
    </label>
  )
}
