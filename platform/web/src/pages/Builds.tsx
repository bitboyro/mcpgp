import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, type Build } from '../api'
import { useSession } from '../session'
import { BuildChip, ErrorBox, Loading, PageHead, Segmented, ago, bytes } from '../ui'
import { BuildButton } from './Overview'

const running = (b?: Build) => b?.status === 'queued' || b?.status === 'running'

export function ServerBuildsPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const { settings } = useSession()
  const [shelf, setShelf] = useState<'active' | 'archived'>('active')
  const builds = useQuery({
    queryKey: ['builds', id, shelf], queryFn: () => api.builds(id, shelf === 'archived'),
    refetchInterval: (q) => q.state.data?.items.some(running) ? 1000 : false,
  })
  const items = builds.data?.items ?? []
  const selectedId = params.get('build') ?? items[0]?.id

  return (
    <>
      <PageHead title="Builds and releases" sub="Every package built for this server. Download, release, archive or delete them.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Builds">
          <div className="card">
            <Segmented label="Builds" value={shelf} onChange={(v) => { setShelf(v); setParams({}, { replace: true }) }}
              options={[['active', 'Active'], ['archived', 'Archived']]} />
            <ErrorBox error={builds.error} />
            {builds.isLoading && <Loading what="builds" />}
            <div className="rows">
              {items.map((b) => (
                <div key={b.id} className="build-row" style={b.id === selectedId ? { background: 'var(--accent-soft)', borderRadius: 12, paddingInline: 10 } : undefined}>
                  <button type="button" className="select" aria-current={b.id === selectedId} onClick={() => setParams({ build: b.id }, { replace: true })}>
                    <span className="v">{b.serverVersion ?? `version ${b.version}`}</span>
                    <span className="meta">by {b.startedBy} · {ago(b.created)} · definition version {b.version}</span>
                  </button>
                  <BuildChip build={b} />
                </div>
              ))}
              {builds.isSuccess && items.length === 0 && <p className="empty">No builds here.</p>}
            </div>
          </div>
          <div className="card">
            <h2>Retention</h2>
            <dl className="kv">
              <dt>Archive after</dt><dd>{settings.retention.archiveAfterDays} days</dd>
              <dt>Delete archived files after</dt><dd>{settings.retention.deleteArchivedAfterDays} days</dd>
              <dt>Always keep</dt><dd>the last {settings.retention.keepLast} builds</dd>
            </dl>
            <p className="hint">Released builds are never deleted automatically. Deleting removes the files; the build record and its activity stay.</p>
          </div>
        </section>
        <section className="stage" aria-label="Selected build">
          {selectedId ? <BuildDetail key={selectedId} id={selectedId} /> : builds.isSuccess && <p className="empty">Build the server to get a package.</p>}
        </section>
      </div>
    </>
  )
}

function BuildDetail({ id }: { id: string }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const build = useQuery({ queryKey: ['build', id], queryFn: () => api.build(id), refetchInterval: (q) => running(q.state.data) ? 1000 : false })
  const b = build.data
  const log = useQuery({ queryKey: ['build-log', id, b?.status], queryFn: () => api.buildLog(id), enabled: !!b, refetchInterval: running(b) ? 1000 : false })
  const files = useQuery({ queryKey: ['artifacts', id], queryFn: () => api.artifacts(id), enabled: b?.status === 'succeeded' && !b.deleted })
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: ['build', id] })
    await queryClient.invalidateQueries({ queryKey: ['builds'] })
  }
  const act = useMutation({
    mutationFn: async (what: 'release' | 'archive' | 'restore' | 'delete') => {
      setNotice(null)
      if (what === 'release') {
        const result = await api.release(id)
        if (result.status === 202) setNotice('Your release is waiting for a person to approve it (API & agents).')
        return
      }
      if (what === 'archive') await api.archiveBuild(id)
      if (what === 'restore') await api.restoreBuild(id)
      if (what === 'delete') await api.deleteBuild(id)
    },
    onSuccess: async () => { setConfirmDelete(false); await refresh() },
  })

  if (build.isLoading) return <Loading what="the build" />
  if (!b) return <ErrorBox error={build.error} />
  const zip = b.artifacts.find((a) => a.name.endsWith('.zip'))
  return (
    <>
      <div className="card-head">
        <div>
          <div className="eyebrow">{b.server}</div>
          <h2>{b.serverVersion ?? `Build of version ${b.version}`}</h2>
          <span className="meta">by {b.startedBy} · {ago(b.created)}{b.releasedBy ? ` · released by ${b.releasedBy}` : ''}</span>
        </div>
        <BuildChip build={b} />
      </div>
      {b.error && <div className="note note-bad">{b.error}</div>}
      {notice && <div className="note">{notice}</div>}
      <ErrorBox error={act.error} />

      {b.status === 'succeeded' && !b.deleted && (
        <div className="stage-card">
          <span className="eyebrow">Download</span>
          {b.artifacts.map((a) => (
            <a key={a.name} className="stage-link" href={api.artifactUrl(b.id, a.name)} download>
              <span className="mono">{a.name}</span><span className="meta">{bytes(a.size)}</span>
            </a>
          ))}
          {zip && <span className="meta mono">sha256 {zip.sha256.slice(0, 12)}…</span>}
        </div>
      )}
      {files.data?.packageFiles && zip && (
        <details className="stage-card">
          <summary>Package contents · {files.data.packageFiles.length} files</summary>
          <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {files.data.packageFiles.map((f) => (
              <li key={f.path} style={{ display: 'flex', justifyContent: 'space-between', gap: 10 }}>
                <a className="mono" style={{ fontSize: 12 }} href={api.artifactUrl(b.id, zip.name, f.path)} download>{f.path}</a>
                <span className="meta">{bytes(f.size)}</span>
              </li>
            ))}
          </ul>
        </details>
      )}

      <div className="actions">
        {b.status === 'succeeded' && !b.released && !b.deleted && can('builds:release') &&
          <button type="button" className="btn btn-primary" disabled={act.isPending} onClick={() => act.mutate('release')}>Release</button>}
        {b.status === 'succeeded' && !b.released && !b.deleted &&
          <Link className="btn" to={`/servers/${b.server}/changes?to=${b.version}`}>What changed since the last release</Link>}
        {!running(b) && !b.archived && can('builds:delete') &&
          <button type="button" className="btn btn-stage" disabled={act.isPending} onClick={() => act.mutate('archive')}>Archive</button>}
        {b.archived && !b.deleted && can('builds:delete') &&
          <button type="button" className="btn btn-stage" disabled={act.isPending} onClick={() => act.mutate('restore')}>Restore</button>}
        {!running(b) && !b.deleted && can('builds:delete') && (!b.released || b.archived) &&
          <button type="button" className="btn btn-stage" onClick={() => setConfirmDelete(true)}>Delete files</button>}
      </div>
      {b.released && !b.archived && !b.deleted && <p className="hint">Released builds are archived before their files can be deleted.</p>}
      {confirmDelete && (
        <div className="stage-card" role="alertdialog" aria-labelledby="delete-title">
          <h3 id="delete-title">Delete the files of {b.serverVersion ?? b.id}?</h3>
          <p>The package files go; the build record and its activity stay. This can't be undone.</p>
          <div className="actions">
            <button type="button" className="btn btn-danger-solid" disabled={act.isPending} onClick={() => act.mutate('delete')}>Delete files permanently</button>
            <button type="button" className="btn btn-stage" onClick={() => setConfirmDelete(false)}>Cancel</button>
          </div>
        </div>
      )}
      <div>
        <h3 style={{ marginBottom: 8 }}>Log</h3>
        <pre className="code" aria-label="Build log">{log.data ?? ''}</pre>
      </div>
    </>
  )
}

export function AllBuildsPage() {
  const servers = useQuery({ queryKey: ['servers'], queryFn: () => api.servers() })
  const lists = useQueries({ queries: (servers.data?.items ?? []).map((s) => ({ queryKey: ['builds', s.id], queryFn: () => api.builds(s.id) })) })
  const all = lists.flatMap((l) => l.data?.items ?? []).sort((a, b) => b.created.localeCompare(a.created))
  return (
    <>
      <PageHead title="All builds" sub="Every active build of every server, newest first." />
      <div className="body">
        <section className="col col-wide" aria-label="Builds">
          <div className="card">
            {servers.isLoading && <Loading what="builds" />}
            <div className="rows">
              {all.map((b) => (
                <div key={b.id} className="build-row">
                  <Link to={`/servers/${b.server}/builds?build=${b.id}`} style={{ textDecoration: 'none', color: 'inherit' }}>
                    <span className="v mono">{b.server}</span> <span className="v">{b.serverVersion ?? ''}</span><br />
                    <span className="meta">by {b.startedBy} · {ago(b.created)} · definition version {b.version}</span>
                  </Link>
                  <BuildChip build={b} />
                </div>
              ))}
              {servers.isSuccess && lists.every((l) => l.isSuccess) && all.length === 0 && <p className="empty">No builds yet.</p>}
            </div>
          </div>
        </section>
      </div>
    </>
  )
}
