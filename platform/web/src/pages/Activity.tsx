import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, type Activity, type Approval } from '../api'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, Segmented, ago, Icon } from '../ui'

type Filter = 'all' | 'builds' | 'changes' | 'agents' | 'access'
const FILTERS: Filter[] = ['all', 'builds', 'changes', 'agents', 'access']

/** Build and download events: their target is the build's id. */
export const aboutBuild = (a: Activity) => a.action.startsWith('build.') || a.action.startsWith('artifact.')

const CHANGES = ['server.', 'version.', 'library.', 'variant', 'duplicate']
const ACCESS = ['signin', 'signout', 'identity.', 'token.', 'password.']

function matches(filter: Filter, a: Activity): boolean {
  if (filter === 'builds') return aboutBuild(a)
  if (filter === 'changes') return CHANGES.some((p) => a.action.startsWith(p))
  if (filter === 'agents') return a.actorKind === 'agent' || a.actorKind === 'pipeline'
  if (filter === 'access') return ACCESS.some((p) => a.action.startsWith(p))
  return true
}

export function describe(a: Activity): string {
  const d = a.details ?? {}
  switch (a.action) {
    case 'server.create': return `created ${a.target}`
    case 'version.create': return `saved revision ${d.version ?? ''} of ${a.target}`
    case 'build.start': return `started a build of ${d.server ?? ''} revision ${d.version ?? ''}`
    case 'build.finish': return a.outcome === 'ok' ? `finished build ${a.target}` : `build ${a.target} failed`
    case 'build.release': return `released build ${a.target}`
    case 'artifact.download': return `downloaded ${d.name ?? 'a file'} of build ${a.target}`
    case 'signin': return 'signed in'
    case 'signout': return 'signed out'
    default: return `${a.action.replace('.', ' ')} ${a.target}`
  }
}

export function ActivityPage() {
  const { id } = useParams()
  const [params, setParams] = useSearchParams()
  const filter = FILTERS.find((f) => f === params.get('show')) ?? 'all'
  const build = params.get('build')
  const setFilter = (f: Filter) => setParams(f === 'all' ? {} : { show: f }, { replace: true })
  const activity = useQuery({ queryKey: ['activity', id ?? ''], queryFn: () => api.activity({ server: id, limit: 200 }), refetchInterval: 10_000 })
  const items = (activity.data?.items ?? []).filter((a) => matches(filter, a) && (!build || a.target === build))
  return (
    <>
      <PageHead title="Activity" sub={id
        ? <>Who changed this server, built or released it, and when. The packages themselves are on <Link to={`/servers/${id}/builds`}>Builds and releases</Link>.</>
        : 'Who did what on the platform, and when: changes, builds, releases and sign-ins, by people, pipelines and agents.'} />
      <div className="body">
        <section className="col col-wide" aria-label="Timeline">
          <div className="card">
            <Segmented label="Show" value={filter} onChange={setFilter}
              options={[['all', 'All'], ['changes', 'Changes'], ['builds', 'Builds'], ...(id ? [] : [['access', 'Access'] as [Filter, string]]), ['agents', 'Agents']]} />
            {build && <p className="hint">Only build <span className="mono">{build}</span>. <button type="button" className="how" onClick={() => setFilter(filter)}>Show everything</button></p>}
            <ErrorBox error={activity.error} />
            {activity.isLoading && <Loading what="activity" />}
            <ul className="timeline">
              {items.map((a, i) => (
                <li key={a.ts + i}>
                  <span className={'dot' + (a.outcome !== 'ok' ? ' dot-error' : a.actorKind !== 'person' ? ' dot-agent' : '')} aria-hidden="true">
                    {a.actor.slice(0, 2).toUpperCase()}
                  </span>
                  <span style={{ minWidth: 0 }}>
                    <span><b>{a.actorKind === 'person' ? a.actor : `${a.actorKind}:${a.actor}`}</b> {describe(a)}</span><br />
                    <span className="meta"><time dateTime={a.ts} title={a.ts}>{ago(a.ts)}</time> · {a.action}{a.outcome !== 'ok' ? ` · ${a.outcome}` : ''}
                      {aboutBuild(a) && <> · <Link to={`/builds/${encodeURIComponent(a.target)}`}>Open the build</Link></>}</span>
                  </span>
                </li>
              ))}
              {activity.isSuccess && items.length === 0 && <li className="empty">Nothing here yet.</li>}
            </ul>
          </div>
        </section>
        <aside className="stage stage-narrow" aria-label="Waiting for approval"><Approvals /></aside>
      </div>
    </>
  )
}

export function Approvals() {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const approvals = useQuery({ queryKey: ['approvals'], queryFn: () => api.approvals(), refetchInterval: 15_000 })
  const items = approvals.data?.items ?? []
  return (
    <>
      <div>
        <h2>Waiting for approval</h2>
        <p className="hint">Agents can't release on their own; a person decides.</p>
      </div>
      <ErrorBox error={approvals.error} />
      {items.map((a) => <ApprovalCard key={a.id} approval={a} canDecide={can('approvals:decide')}
        onDone={() => { for (const key of ['approvals', 'builds', 'build', 'activity']) void queryClient.invalidateQueries({ queryKey: [key] }) }} />)}
      {approvals.isSuccess && items.length === 0 && <p className="empty">Nothing is waiting.</p>}
    </>
  )
}

function ApprovalCard({ approval, canDecide, onDone }: { approval: Approval; canDecide: boolean; onDone: () => void }) {
  const [comment, setComment] = useState('')
  const decide = useMutation({ mutationFn: (approve: boolean) => api.decide(approval.id, approve, comment || undefined), onSuccess: onDone })
  const build = useQuery({ queryKey: ['build', approval.target], queryFn: () => api.build(approval.target), enabled: approval.action === 'build.release' })
  return (
    <div className="stage-card">
      <span className="eyebrow">{approval.action === 'build.release' ? 'Release' : approval.action}</span>
      <h3>{build.data ? <Link to={`/servers/${build.data.server}/builds?build=${build.data.id}`}>{build.data.server} {build.data.serverVersion}</Link> : approval.target}</h3>
      <span className="meta">by {approval.requestedBy} · {ago(approval.requestedAt)}</span>
      {canDecide && (
        <>
          <label className="field">Comment (optional)
            <input className="input input-stage" value={comment} onChange={(e) => setComment(e.target.value)} />
          </label>
          <ErrorBox error={decide.error} />
          <div className="actions">
            <button type="button" className="btn btn-primary btn-sm" disabled={decide.isPending} onClick={() => decide.mutate(true)}><Icon name="check" />Approve and release</button>
            <button type="button" className="btn btn-stage btn-sm" disabled={decide.isPending} onClick={() => decide.mutate(false)}>Reject</button>
          </div>
        </>
      )}
    </div>
  )
}
