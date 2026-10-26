import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type ContractPlan } from '../api'
import { readPicked, type PickedProject } from '../files'
import { specProblems } from '../specUpload'
import { SpecPicker, SpecProblemsView, type SpecUpload } from './SpecUpload'
import { useManifest, useServer } from '../serverData'
import { ShippedVersions } from './ShippedVersions'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, ago, shortDigest, Icon } from '../ui'

/** API definitions: the OpenAPI documents the server is made from, the versions it ships, and its saved versions. */
export function ApiDefinitionsPage() {
  const { id = '' } = useParams()
  const { can } = useSession()
  const server = useServer(id)
  const manifest = useManifest(id)
  const [chosen, setChosen] = useState<number | null>(null)
  const versionNumber = chosen ?? server.data?.latestVersion ?? null
  const version = useQuery({ queryKey: ['version', id, versionNumber], queryFn: () => api.version(id, versionNumber!), enabled: versionNumber != null })
  const [file, setFile] = useState<string | null>(null)
  const path = file ?? version.data?.root ?? null
  const content = useQuery({ queryKey: ['file', id, versionNumber, path], queryFn: () => api.file(id, versionNumber!, path!), enabled: !!path && versionNumber != null })
  const checks = useMutation({ mutationFn: () => api.validate(id, versionNumber!) })

  return (
    <>
      <PageHead title="API definitions" sub="The OpenAPI documents the server is built from, the API versions it ships, and its saved revisions.">
        <button type="button" className="btn" disabled={versionNumber == null || checks.isPending} onClick={() => checks.mutate()}><Icon name="checks" />Validate</button>
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Revisions">
          <ErrorBox error={server.error} />
          {server.isLoading && <Loading />}
          {server.data && <Contracts id={id} editable={can('servers:write') && !server.data.system} onDone={(n) => { setChosen(n); setFile(null) }} />}
          {manifest.data && <ShippedVersions id={id} manifest={manifest.data} />}
          {can('servers:write') && server.data && !server.data.system && <Upload id={id} onDone={(n) => { setChosen(n); setFile(null) }} />}
          {server.data && (
            <div className="card">
              <h2>Revisions</h2>
              <div className="list" role="listbox" aria-label="Revisions">
                {[...server.data.versions].reverse().map((v) => (
                  <button key={v.number} type="button" role="option" aria-selected={v.number === versionNumber} className="tool-item"
                    onClick={() => { setChosen(v.number); setFile(null); checks.reset() }}>
                    <span>
                      <b>Revision {v.number}</b>{v.number === server.data.latestVersion && <span className="hint"> · latest</span>}
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
              <h2>Validation results for revision {versionNumber}</h2>
              <ErrorBox error={checks.error} />
              {checks.data && (
                <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {checks.data.diagnostics.length === 0 && <li className="hint">No findings.</li>}
                  {checks.data.diagnostics.map((d, i) => (
                    <li key={i} className="row" style={{ flexDirection: 'column', alignItems: 'flex-start', gap: 2 }}>
                      <span className={'chip chip-' + (d.level === 'error' ? 'bad' : d.level === 'warning' ? 'warn' : 'idle')}>{d.level} {d.code}</span>
                      <span style={{ fontSize: 14 }}>{d.message}</span>
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
                  <div className="eyebrow">Revision {version.data.number}</div>
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
                  <h3 className="mono" style={{ fontSize: 14 }}>{path}</h3>
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

interface ContractRef { version: string; spec?: string; default?: boolean; source?: string }
interface ApiRef { id: string; title?: string; contracts?: ContractRef[] }

/**
 * The API specs of the server, and adding or replacing one from an uploaded spec or from the address
 * where it's published (each makes the next version); a version fetched from an address can be
 * checked for updates.
 */
function Contracts({ id, editable, onDone }: { id: string; editable: boolean; onDone: (version: number) => void }) {
  const queryClient = useQueryClient()
  const definition = useQuery({ queryKey: ['definition', id, 'latest'], queryFn: () => api.definition(id, 'latest') })
  const [open, setOpen] = useState<string | null>(null)        // '' for a new version, else the version to replace
  const [apiId, setApiId] = useState('')
  const [files, setFiles] = useState<SpecUpload | null>(null)
  const [mode, setMode] = useState<'files' | 'url'>('files')
  const [url, setUrl] = useState('')
  const [fetchUrl, setFetchUrl] = useState('')         // the address the plan was asked for (on Fetch, not per keystroke)
  const [rev, setRev] = useState(0)
  const [main, setMain] = useState('')
  const [version, setVersion] = useState<string | null>(null)
  const [makeDefault, setMakeDefault] = useState(false)
  const root = definition.data?.root as { api?: ApiRef; apis?: ApiRef[] } | undefined
  const apis: ApiRef[] = root?.api ? [root.api] : root?.apis ?? []
  const target = apiId || apis[0]?.id || ''
  const params = { api: apis.length > 1 ? target : undefined, version: version ?? (open || undefined), main: main || undefined, default: makeDefault || undefined }
  const source = mode === 'url' ? (fetchUrl ? { url: fetchUrl } : null) : files
  const plan = useQuery({
    queryKey: ['contract-plan', id, rev, main, version, open, target, makeDefault, mode, fetchUrl],
    queryFn: () => api.contractPlan(id, source!, params),
    enabled: source !== null && open !== null,
    retry: false,
    gcTime: 0,
  })
  const save = useMutation({
    mutationFn: () => api.addContract(id, source!, params),
    onSuccess: async (server) => {
      for (const key of ['server', 'manifest', 'definition', 'servers', 'operations', 'naming']) await queryClient.invalidateQueries({ queryKey: [key] })
      close()
      if (server.latestVersion != null) onDone(server.latestVersion)
    },
  })
  const close = () => { setOpen(null); setFiles(null); setMain(''); setVersion(null); setMakeDefault(false); setMode('files'); setUrl(''); setFetchUrl('') }
  const checkForUpdates = (apiOf: string, c: ContractRef) => {
    setApiId(apiOf); setOpen(c.version); setMode('url'); setUrl(c.source!); setFetchUrl(c.source!); setRev((r) => r + 1)
  }
  const problems = specProblems(plan.error)
  if (definition.isLoading) return <Loading what="the API definitions" />
  if (!definition.data) return <ErrorBox error={definition.error} />
  return (
    <div className="card">
      <div className="card-head">
        <h2>API versions</h2>
        {editable && open === null && apis.length > 0 && <button type="button" className="btn btn-sm" onClick={() => setOpen('')}><Icon name="plus" />Add an API version</button>}
      </div>
      {apis.length === 0 && <p className="hint">This server gets its APIs from the server it extends.</p>}
      {apis.map((a) => (
        <div key={a.id} className="list" style={{ gap: 4 }}>
          {apis.length > 1 && <b>{a.title ?? a.id}</b>}
          {(a.contracts ?? []).map((c) => (
            <div key={c.version} className="row" style={{ background: 'none', padding: '6px 0' }}>
              <span style={{ minWidth: 0 }}>
                API version <b>{c.version}</b>{c.default && <> <span className="tag">default</span></>}<br />
                <span className="meta mono" style={{ overflowWrap: 'anywhere' }}>{c.spec}</span>
                {c.source && <><br /><span className="meta" style={{ overflowWrap: 'anywhere' }}>From <span className="mono">{c.source}</span></span></>}
              </span>
              {editable && open === null && (
                <span className="actions" style={{ flexShrink: 0 }}>
                  {c.source && <button type="button" className="btn btn-sm" onClick={() => checkForUpdates(a.id, c)}>Check for updates</button>}
                  <button type="button" className="btn btn-sm" onClick={() => { setApiId(a.id); setOpen(c.version) }}>Replace</button>
                </span>
              )}
            </div>
          ))}
        </div>
      ))}
      {open !== null && (
        <form className="list" style={{ gap: 12 }} aria-label="API version" onSubmit={(e) => { e.preventDefault(); if (plan.data?.compiles) save.mutate() }}>
          <h3>{open ? `Replace the API definition of version ${open}` : 'Add an API version'}</h3>
          {apis.length > 1 && !open && (
            <label className="field">API
              <select className="input" value={target} onChange={(e) => setApiId(e.target.value)}>
                {apis.map((a) => <option key={a.id} value={a.id}>{a.title ?? a.id}</option>)}
              </select>
            </label>
          )}
          <div className="actions" role="radiogroup" aria-label="Where the API definition comes from">
            <button type="button" role="radio" aria-checked={mode === 'files'} className={'btn btn-sm' + (mode === 'files' ? ' btn-primary' : '')}
              onClick={() => { setMode('files'); save.reset() }}>Upload files</button>
            <button type="button" role="radio" aria-checked={mode === 'url'} className={'btn btn-sm' + (mode === 'url' ? ' btn-primary' : '')}
              onClick={() => { setMode('url'); save.reset() }}>From an address</button>
          </div>
          {mode === 'files' && <SpecPicker files={files} onFiles={(f) => { setFiles(f); setRev((r) => r + 1); save.reset() }} />}
          {mode === 'url' && (
            <div className="list" style={{ gap: 6 }}>
              <div className="actions">
                <label className="field" style={{ flex: '1 1 320px' }}><span className="visually-hidden">Address of the API definition</span>
                  <input className="input mono" type="url" value={url} placeholder="https://api.acme.com/openapi.json"
                    onChange={(e) => setUrl(e.target.value)} />
                </label>
                <button type="button" className="btn btn-sm" disabled={!url.trim() || plan.isFetching}
                  onClick={() => { setFetchUrl(url.trim()); setRev((r) => r + 1); save.reset() }}>Fetch</button>
              </div>
              <p className="hint">The platform fetches the API definition from this address now, and again whenever you check for updates. Only hosts an
                administrator allows (specHosts) can be used; one spec file, with no references to other files.</p>
            </div>
          )}
          {plan.isFetching && <p className="hint">{mode === 'url' ? 'Fetching the API definition…' : 'Reading the API definition…'}</p>}
          {problems && <SpecProblemsView problems={problems} main={main} onMain={setMain} />}
          {plan.error && !problems && <ErrorBox error={plan.error} />}
          {plan.data && (
            <>
              {!open && (
                <label className="field">API version (from info.version in the definition)
                  <input className="input" value={version ?? plan.data.version} onChange={(e) => setVersion(e.target.value)} />
                </label>
              )}
              <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 14 }}>
                <input type="checkbox" checked={makeDefault} onChange={(e) => setMakeDefault(e.target.checked)} /> Make it the default version
              </label>
              {plan.data.unchanged
                ? <div className="note note-ok">No changes: the API definition at this address matches the saved one.</div>
                : <p className="hint">{plan.data.replaces ? `Replaces the API definition of version ${plan.data.version}` : `Adds API version ${plan.data.version}`}
                  {' '}(files under <span className="mono">{plan.data.spec.replace(/[^/]+$/, '')}</span>).</p>}
              {plan.data.summary && !plan.data.unchanged && <PlannedChanges plan={plan.data} />}
              {!plan.data.compiles && (
                <div className="note note-bad">With this API definition the server doesn't compile: {plan.data.diagnostics.filter((d) => d.level === 'error').map((d) => d.message).join('; ')}</div>
              )}
            </>
          )}
          <ErrorBox error={save.error} />
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={!plan.data?.compiles || plan.data.unchanged || save.isPending}><Icon name="save" />Save as the next revision</button>
            <button type="button" className="btn" onClick={close}>Cancel</button>
          </div>
        </form>
      )}
    </div>
  )
}

/** What agents would notice with the new spec, before it's saved (the same list as the Changes page). */
function PlannedChanges({ plan }: { plan: ContractPlan }) {
  const changes = plan.changes ?? []
  const shown = changes.slice(0, 8)
  return (
    <div className={'note' + (plan.breaking ? ' note-warn' : '')}>
      <b>{plan.summary}</b>
      {shown.length > 0 && (
        <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
          {shown.map((c, i) => <li key={i}>{c.impact === 'breaking' && <b>Breaking: </b>}{c.tool && <span className="mono">{c.tool}</span>}{c.tool && ': '}{c.text}</li>)}
        </ul>
      )}
      {changes.length > shown.length && <p className="hint" style={{ margin: '4px 0 0' }}>and {changes.length - shown.length} more; after saving, the Changes page lists them all.</p>}
    </div>
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
      <h2>Upload the whole project</h2>
      <p className="hint">For projects kept in git: the folder with the <code>*.mcpgp.yaml</code>, or a zip of it, as the next revision.
        A revision with the same content as the latest isn't created again.</p>
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
      <div><button type="submit" className="btn btn-primary" disabled={!picked || upload.isPending}><Icon name="upload" />Upload project</button></div>
    </form>
  )
}
