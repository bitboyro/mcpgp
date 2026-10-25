import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Operation, type OperationList, type Select } from '../api'
import { asList, changes, entryFor, same, turnOff, turnOffAll, turnOn, whyOff, type TurnOn } from '../selection'
import { useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, Segmented } from '../ui'
import { Checks, DefinitionDiff } from './Overview'

type StateFilter = 'all' | 'on' | 'off'

const isOn = (op: Operation) => op.state === 'tool' || op.state === 'resource'
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE']

/** The resource an operation is on: its path's first segment that isn't a version or a parameter (`/v1/partners/{id}` → `/partners`). */
export function resourceOf(path: string): string {
  const segments = path.split('/').filter((s) => s && !s.startsWith('{'))
  const named = segments.find((s) => !/^(v\d+(\.\d+)*|api)$/i.test(s))
  return '/' + (named ?? segments[0] ?? '')
}

interface Asked {
  api: string
  op: Operation
  result: TurnOn
  others: number
}

/**
 * Every operation of the contracts with a switch: on makes it a tool (or resource), off leaves it out.
 * Switches change the API's `select` block; unsaved changes are previewed by the platform (a dry run),
 * and saving makes the next version, like the settings on Overview.
 */
export function OperationsView({ id, settings }: { id: string; settings?: ReactNode }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const server = useServer(id)
  const base = server.data?.latestVersion ?? 0
  const current = useQuery({ queryKey: ['operations', id, base], queryFn: () => api.operations(id, base), enabled: base > 0, retry: false })
  const [working, setWorking] = useState<Record<string, Select>>({})
  const [asked, setAsked] = useState<Asked | null>(null)
  const [message, setMessage] = useState('')
  const [search, setSearch] = useState('')
  const [stateFilter, setStateFilter] = useState<StateFilter>('all')
  const [tag, setTag] = useState('')
  const [apiFilter, setApiFilter] = useState('')
  const [methods, setMethods] = useState<string[]>([])
  const [resource, setResource] = useState('')
  /** Switches flipped while their preview is on its way: shown at once, replaced by the preview when it comes. */
  const [flips, setFlips] = useState<Record<string, boolean>>({})

  const edits = useMemo(() => (current.data?.apis ?? []).flatMap((a) => (working[a.id] ? changes(a.id, a.select, working[a.id]) : [])),
    [current.data, working])
  const preview = useQuery({
    queryKey: ['edit-preview', id, base, edits, 'operations'],
    queryFn: () => api.previewEdit(id, base, edits, true),
    enabled: edits.length > 0 && base > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const broken = edits.length > 0 && preview.data && !preview.data.operations
  const shown: OperationList | undefined = edits.length > 0 && preview.data?.operations ? preview.data.operations : current.data
  const busy = edits.length > 0 && preview.isFetching
  useEffect(() => {
    if (!preview.isFetching || edits.length === 0) setFlips({})
  }, [preview.isFetching, edits.length])
  const onOf = (apiId: string, op: Operation) => flips[apiId + ' ' + op.key] ?? isOn(op)
  const editable = can('servers:write') && !!current.data && !server.data?.archived && !server.data?.system

  const before = useMemo(() => {
    const map = new Map<string, Operation>()
    current.data?.apis.forEach((a) => a.operations.forEach((o) => map.set(a.id + ' ' + o.key, o)))
    return map
  }, [current.data])
  const flipped = useMemo(() => {
    const on: string[] = []
    const off: string[] = []
    if (edits.length === 0) return { on, off }
    shown?.apis.forEach((a) => a.operations.forEach((o) => {
      const was = before.get(a.id + ' ' + o.key)
      if (was && isOn(was) !== isOn(o)) (isOn(o) ? on : off).push(o.tool ?? was.tool ?? `${o.method} ${o.path}`)
    }))
    return { on, off }
  }, [shown, before, edits.length])
  const summary = [flipped.on.length ? 'on: ' + flipped.on.join(', ') : '', flipped.off.length ? 'off: ' + flipped.off.join(', ') : '']
    .filter(Boolean).join('; ')
  const defaultMessage = ('Tools: ' + (summary || 'selection changed')).slice(0, 200)

  const save = useMutation({
    mutationFn: () => api.saveEdit(id, base, edits, message || defaultMessage),
    onSuccess: async () => {
      setWorking({})
      setFlips({})
      setMessage('')
      for (const key of ['server', 'manifest', 'servers', 'operations', 'example-config', 'version']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })

  const selectOf = (apiId: string): Select => working[apiId] ?? current.data?.apis.find((a) => a.id === apiId)?.select ?? {}
  const apply = (apiId: string, next: Select) => {
    setWorking((now) => ({ ...now, [apiId]: next }))
    setAsked(null)
    save.reset()
  }

  const flip = (apiId: string, op: Operation, ops: Operation[]) => {
    const key = apiId + ' ' + op.key
    const select = selectOf(apiId)
    if (onOf(apiId, op)) {
      apply(apiId, turnOff(select, op))
      setFlips((now) => ({ ...now, [key]: false }))
      return
    }
    if (flips[key] === false) {
      // turned off a moment ago, before the preview came back: take that entry out again
      const list = asList(select.exclude)
      const index = list.map((e) => same(e, entryFor(op))).lastIndexOf(true)
      if (index >= 0) {
        apply(apiId, { ...select, exclude: list.filter((_, i) => i !== index) })
        setFlips((now) => ({ ...now, [key]: true }))
      }
      return
    }
    const result = turnOn(select, op)
    if (!result) return
    if (!result.broad) {
      apply(apiId, result.select)
      setFlips((now) => ({ ...now, [key]: true }))
      return
    }
    const e = op.exclusion
    const others = ops.filter((o) => o !== op && o.state === 'excluded' && o.exclusion?.kind === e?.kind
      && (e?.kind !== 'exclude' || o.exclusion?.index === e.index) && (e?.kind !== 'access' || o.exclusion?.reason === e.reason)).length
    setAsked({ api: apiId, op, result, others })
  }

  const apis = shown?.apis ?? []
  const tags = useMemo(() => [...new Set(apis.flatMap((a) => a.operations.flatMap((o) => o.tags)))].sort(), [apis])
  const needle = search.trim().toLowerCase()
  const allOps = apis.flatMap((a) => a.operations)
  const presentMethods = METHODS.filter((m) => allOps.some((o) => o.method === m))
  const resources = useMemo(() => {
    const count = new Map<string, number>()
    allOps.forEach((o) => count.set(resourceOf(o.path), (count.get(resourceOf(o.path)) ?? 0) + 1))
    return [...count.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [allOps])
  const narrowed = methods.length > 0 || !!resource
  const visible = apis.filter((a) => !apiFilter || a.id === apiFilter).map((a) => ({
    ...a,
    operations: a.operations.filter((o) => (stateFilter === 'all' || (stateFilter === 'on') === onOf(a.id, o))
      && (!tag || o.tags.includes(tag))
      && (methods.length === 0 || methods.includes(o.method))
      && (!resource || resourceOf(o.path) === resource)
      && (!needle || [o.path, o.method, o.operationId ?? '', o.summary ?? '', o.tool ?? ''].some((v) => v.toLowerCase().includes(needle)))),
  }))
  const shownCount = visible.reduce((n, a) => n + a.operations.length, 0)

  return (
    <div className="body">
      <section className="col col-wide" aria-label="Operations">
        {settings}
        <ErrorBox error={server.error ?? current.error} />
        {current.error instanceof ApiError && current.error.status === 422 && (
          <p className="hint">The latest version doesn't compile; the checks on <Link to={`/servers/${id}/apis`}>API definitions</Link> say why.</p>
        )}
        {current.isLoading && <Loading what="operations" />}
        {shown && (
          <div className="card">
            <div className="filters">
              <label className="field" style={{ flex: '1 1 220px' }}><span className="visually-hidden">Search operations</span>
                <input className="input" type="search" placeholder="Search paths, operation ids, tools" value={search} onChange={(e) => setSearch(e.target.value)} />
              </label>
              <Segmented label="Show" value={stateFilter} onChange={setStateFilter} options={[['all', 'All'], ['on', 'On'], ['off', 'Off']]} />
              {tags.length > 0 && (
                <label className="field"><span className="visually-hidden">Tag</span>
                  <select className="input" value={tag} onChange={(e) => setTag(e.target.value)}>
                    <option value="">Every tag</option>
                    {tags.map((t) => <option key={t} value={t}>{t}</option>)}
                  </select>
                </label>
              )}
              {resources.length > 1 && (
                <label className="field"><span className="visually-hidden">Resource</span>
                  <select className="input" value={resource} onChange={(e) => setResource(e.target.value)}>
                    <option value="">Every resource</option>
                    {resources.map(([r, n]) => <option key={r} value={r}>{r} ({n})</option>)}
                  </select>
                </label>
              )}
              {presentMethods.length > 1 && (
                <div className="segmented" role="group" aria-label="Methods">
                  {presentMethods.map((m) => (
                    <button key={m} type="button" aria-pressed={methods.includes(m)}
                      onClick={() => setMethods((now) => now.includes(m) ? now.filter((x) => x !== m) : [...now, m])}>{m}</button>
                  ))}
                </div>
              )}
              {apis.length > 1 && (
                <label className="field"><span className="visually-hidden">API</span>
                  <select className="input" value={apiFilter} onChange={(e) => setApiFilter(e.target.value)}>
                    <option value="">Every API</option>
                    {apis.map((a) => <option key={a.id} value={a.id}>{a.title}</option>)}
                  </select>
                </label>
              )}
            </div>
            {visible.map((a) => {
              const onShown = a.operations.filter((o) => onOf(a.id, o))
              const byTag = !!tag && !needle && !narrowed
              return (
                <div key={a.id} className="list">
                  <div className="card-head">
                    <span className="hint">{apis.length > 1 && <b>{a.title} · </b>}{a.operations.length} shown, {onShown.length} on</span>
                    {editable && onShown.length > 1 && (
                      <button type="button" className="btn btn-sm"
                        onClick={() => {
                          apply(a.id, turnOffAll(selectOf(a.id), onShown, byTag ? tag : undefined))
                          setFlips((now) => ({ ...now, ...Object.fromEntries(onShown.map((o) => [a.id + ' ' + o.key, false])) }))
                        }}>
                        Turn off {byTag ? `tag ${tag}` : `these ${onShown.length}`}
                      </button>
                    )}
                  </div>
                  <div className="op-list" role="list" aria-label={`Operations of ${a.title}`}>
                    {a.operations.map((o) => {
                      const was = before.get(a.id + ' ' + o.key)
                      const on = onOf(a.id, o)
                      const changed = !!was && isOn(was) !== on
                      const label = `${o.method} ${o.path}`
                      return (
                        <div key={o.key} role="listitem" className={'op-row' + (changed ? ' changed' : '')}>
                          <button type="button" role="switch" className="switch" aria-checked={on} aria-label={label}
                            disabled={!editable || o.state === 'hidden'}
                            title={o.state === 'hidden' ? 'A rule hides it; change the rule in the definition\'s files (API definitions)' : undefined}
                            onClick={() => flip(a.id, o, a.operations)} />
                          <span className="what">
                            <span className="line"><span className="method">{o.method}</span><span className="path">{o.path}</span>
                              {o.deprecated && <span className="tag">deprecated</span>}
                              {changed && <span className="tag">changed</span>}
                            </span>
                            <small>{[o.operationId, o.summary].filter(Boolean).join(' · ')}{o.tags.length > 0 && ` · ${o.tags.join(', ')}`}</small>
                            <small>{on !== isOn(o) ? (on ? 'turning on…' : 'turning off…') : on ? <>{o.state === 'resource' ? 'resource ' : 'tool '}<span className="mono">{o.tool}</span></> : whyOff(o)}</small>
                            {asked && asked.api === a.id && asked.op.key === o.key && (
                              <span className="note note-warn" role="alertdialog" aria-label="Confirm" style={{ marginTop: 6 }}>
                                Turning it on {asked.result.broad}
                                {asked.others > 0 ? `, which turns on ${asked.others} other operation${asked.others === 1 ? '' : 's'} too.` : '.'}
                                <span className="actions" style={{ marginTop: 8 }}>
                                  <button type="button" className="btn btn-sm btn-primary" onClick={() => apply(asked.api, asked.result.select)}>Turn on</button>
                                  <button type="button" className="btn btn-sm" onClick={() => setAsked(null)}>Cancel</button>
                                </span>
                              </span>
                            )}
                          </span>
                        </div>
                      )
                    })}
                    {a.operations.length === 0 && <p className="empty">No operation matches.</p>}
                  </div>
                </div>
              )
            })}
            {shownCount === 0 && visible.length > 1 && <p className="empty">No operation matches.</p>}
          </div>
        )}
      </section>
      <section className="stage stage-narrow" aria-label="Changes">
        {edits.length === 0 ? (
          <>
            <div>
              <div className="eyebrow">How the switches work</div>
              <h2>{current.data ? count(current.data) : 'Operations'}</h2>
            </div>
            <p>Turning a tool off adds an <span className="mono">exclude</span> entry for that operation to the API's{' '}
              <span className="mono">select</span>. Turning one on undoes what left it out; when that reaches further than one
              operation, you're asked first.</p>
            <p className="hint">Changes are previewed before you save, and saving makes the next version, with your name in Activity.
              Operations a rule hides change in the rule's file, on <Link to={`/servers/${id}/apis`}>API definitions</Link>.</p>
          </>
        ) : (
          <>
            <div className="card-head">
              <div>
                <div className="eyebrow">Unsaved</div>
                <h2>{flipped.on.length} on, {flipped.off.length} off</h2>
              </div>
              <span className="live">{busy ? 'Updating…' : 'Preview'}</span>
            </div>
            <ErrorBox error={preview.error} />
            {broken && (
              <>
                <div className="note note-bad">With these changes the definition doesn't compile.</div>
                <Checks diagnostics={preview.data?.diagnostics ?? []} />
              </>
            )}
            {flipped.on.length > 0 && <p><b>On:</b> <span className="mono">{flipped.on.join(', ')}</span></p>}
            {flipped.off.length > 0 && <p><b>Off:</b> <span className="mono">{flipped.off.join(', ')}</span></p>}
            <form className="list" onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
              <label className="field">What changed (optional)
                <input className="input input-stage" value={message} placeholder={defaultMessage} maxLength={200} onChange={(e) => setMessage(e.target.value)} />
              </label>
              <ErrorBox error={save.error} />
              {save.error instanceof ApiError && save.error.status === 409 && (
                <p className="hint">Someone saved a newer version.{' '}
                  <button type="button" className="btn btn-sm btn-stage" onClick={() => { setWorking({}); save.reset(); void queryClient.invalidateQueries({ queryKey: ['server', id] }) }}>Reload</button></p>
              )}
              <div className="actions">
                <button type="submit" className="btn btn-primary" disabled={!!broken || busy || save.isPending}>Save as version {base + 1}</button>
                <button type="button" className="btn btn-stage" onClick={() => { setWorking({}); setFlips({}); setAsked(null); save.reset() }}>Discard</button>
              </div>
            </form>
            <DefinitionDiff id={id} version={base} after={preview.data?.definition} />
          </>
        )}
      </section>
    </div>
  )
}

function count(list: OperationList): string {
  const ops = list.apis.flatMap((a) => a.operations)
  const on = ops.filter(isOn).length
  return `${on} of ${ops.length} operations on`
}
