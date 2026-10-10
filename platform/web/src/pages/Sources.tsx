import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api } from '../api'
import { readPicked, type PickedProject } from '../files'
import { useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, ago, shortDigest } from '../ui'

export function SourcesPage() {
  const { id = '' } = useParams()
  const { can } = useSession()
  const server = useServer(id)
  const [chosen, setChosen] = useState<number | null>(null)
  const versionNumber = chosen ?? server.data?.latestVersion ?? null
  const version = useQuery({ queryKey: ['version', id, versionNumber], queryFn: () => api.version(id, versionNumber!), enabled: versionNumber != null })
  const [file, setFile] = useState<string | null>(null)
  const path = file ?? version.data?.root ?? null
  const content = useQuery({ queryKey: ['file', id, versionNumber, path], queryFn: () => api.file(id, versionNumber!, path!), enabled: !!path && versionNumber != null })
  const checks = useMutation({ mutationFn: () => api.validate(id, versionNumber!) })

  return (
    <>
      <PageHead title="Sources" sub="Where the definition and its files come from: a project you upload, one version at a time.">
        <button type="button" className="btn" disabled={versionNumber == null || checks.isPending} onClick={() => checks.mutate()}>Run checks</button>
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Versions">
          <ErrorBox error={server.error} />
          {server.isLoading && <Loading />}
          {can('servers:write') && server.data && <Upload id={id} onDone={(n) => { setChosen(n); setFile(null) }} />}
          {server.data && (
            <div className="card">
              <h2>Versions</h2>
              <div className="list" role="listbox" aria-label="Versions">
                {[...server.data.versions].reverse().map((v) => (
                  <button key={v.number} type="button" role="option" aria-selected={v.number === versionNumber} className="tool-item"
                    onClick={() => { setChosen(v.number); setFile(null); checks.reset() }}>
                    <span>
                      <b>Version {v.number}</b>{v.number === server.data.latestVersion && <span className="hint"> · latest</span>}
                      <small>{v.message ?? 'no message'} · {v.author} {ago(v.created)}</small>
                    </span>
                    <span className="meta mono">{shortDigest(v.digest)}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
          {(checks.data || checks.error) && (
            <div className="card">
              <h2>Checks for version {versionNumber}</h2>
              <ErrorBox error={checks.error} />
              {checks.data && (
                <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {checks.data.diagnostics.length === 0 && <li className="hint">No findings.</li>}
                  {checks.data.diagnostics.map((d, i) => (
                    <li key={i} className="row" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
                      <span className={'chip chip-' + (d.level === 'error' ? 'bad' : d.level === 'warning' ? 'warn' : 'idle')}>{d.level} {d.code}</span>
                      <span style={{ fontSize: 13 }}>{d.message}</span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </section>
        <section className="stage" aria-label="Resolved project">
          {version.data && (
            <>
              <div className="card-head">
                <div>
                  <div className="eyebrow">Version {version.data.number}</div>
                  <h2>{version.data.files.length} files</h2>
                </div>
                <span className="meta mono">{shortDigest(version.data.digest)}</span>
              </div>
              <div className="list" role="listbox" aria-label="Files" style={{ gap: 2 }}>
                {version.data.files.map((f) => (
                  <button key={f.path} type="button" role="option" aria-selected={f.path === path} className="file-btn"
                    style={{ color: f.path === path ? 'var(--ink)' : 'var(--stage-code)' }} onClick={() => setFile(f.path)}>
                    <span>{f.path}</span>
                    <span>{f.path === version.data.root ? 'definition' : f.path.split('/')[0] === f.path ? '' : f.path.split('/')[0]}</span>
                  </button>
                ))}
              </div>
              {path && (
                <>
                  <h3 className="mono" style={{ fontSize: 13 }}>{path}</h3>
                  <ErrorBox error={content.error} />
                  {content.data !== undefined && <pre className="code" aria-label={`Content of ${path}`}>{content.data}</pre>}
                </>
              )}
            </>
          )}
          {server.data && server.data.latestVersion == null && <p className="empty">No version yet.</p>}
        </section>
      </div>
    </>
  )
}

function Upload({ id, onDone }: { id: string; onDone: (version: number) => void }) {
  const queryClient = useQueryClient()
  const folder = useRef<HTMLInputElement>(null)
  const zip = useRef<HTMLInputElement>(null)
  const [picked, setPicked] = useState<PickedProject | null>(null)
  const [message, setMessage] = useState('')
  const upload = useMutation({
    mutationFn: () => api.addVersion(id, picked!.files, message || undefined),
    onSuccess: async (server) => {
      setPicked(null)
      setMessage('')
      await queryClient.invalidateQueries({ queryKey: ['server', id] })
      await queryClient.invalidateQueries({ queryKey: ['manifest', id] })
      await queryClient.invalidateQueries({ queryKey: ['servers'] })
      if (server.latestVersion != null) onDone(server.latestVersion)
    },
  })
  const pick = async (list: FileList | null) => {
    if (list && list.length > 0) setPicked(await readPicked(list))
  }
  return (
    <form className="card" onSubmit={(e) => { e.preventDefault(); upload.mutate() }}>
      <h2>Upload a new version</h2>
      <p className="hint">The whole project, as a folder or a zip. A version with the same content as the latest isn't created again.</p>
      <div className="actions">
        <button type="button" className="btn" onClick={() => folder.current?.click()}>Choose folder</button>
        <button type="button" className="btn" onClick={() => zip.current?.click()}>Choose zip</button>
        <input ref={folder} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true" {...{ webkitdirectory: '', directory: '' }} multiple
          onChange={(e) => pick(e.target.files)} />
        <input ref={zip} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true" accept=".zip,application/zip" onChange={(e) => pick(e.target.files)} />
        {picked && <span className="hint">{picked.files instanceof Blob ? 'a zip' : `${picked.count} files`} from <span className="mono">{picked.name}</span></span>}
      </div>
      <label className="field">What changed (optional)
        <input className="input" value={message} onChange={(e) => setMessage(e.target.value)} maxLength={200} />
      </label>
      <ErrorBox error={upload.error} />
      <div><button type="submit" className="btn btn-primary" disabled={!picked || upload.isPending}>Upload version</button></div>
    </form>
  )
}
