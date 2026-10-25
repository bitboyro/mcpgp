import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, type PackagePreview } from '../api'
import { Markdown } from '../markdown'
import { useManifest, useServer } from '../serverData'
import { ErrorBox, Loading, PageHead, Segmented } from '../ui'
import { BuildButton } from './Overview'

const FORMATS: { id: PackagePreview['formats'][number]['id']; title: string; use: string }[] = [
  { id: 'jar', title: 'Java jar', use: 'Runs wherever Java 21 runs: java -jar. stdio for desktop clients, HTTP for shared use. Always in the package.' },
  { id: 'mcpb', title: 'Desktop bundle (.mcpb)', use: 'One file for Claude Desktop and other MCP bundle clients: open it, then fill in the target server and sign-in.' },
  { id: 'docker', title: 'Docker', use: 'A Dockerfile and a compose file: docker compose up, with the config in /config and data in /data.' },
]

const DOC_LABELS: Record<string, string> = {
  'docs/INSTALL.md': 'Install', 'docs/README.md': 'Readme', 'docs/CONFIGURATION.md': 'Configuration', 'docs/TOOLS.md': 'Tools',
  'docs/OPERATIONS.md': 'Operations', 'AGENTS.md': 'For agents', 'llms.txt': 'llms.txt',
}

/**
 * Packaging (design/ui.md): what the next build of the server contains and how the customer
 * installs it, before anything is built: the formats, the docs as they ship, the files, and the
 * commands a pipeline runs.
 */
export function PackagingPage() {
  const { id = '' } = useParams()
  const server = useServer(id)
  const manifest = useManifest(id, 'latest', server.data?.latestVersion != null)
  const base = server.data?.latestVersion ?? 0
  const preview = useQuery({ queryKey: ['package', id, base], queryFn: () => api.packagePreview(id, base), enabled: base > 0, retry: false })
  const version = useQuery({ queryKey: ['version', id, base], queryFn: () => api.version(id, base), enabled: base > 0 })
  const builds = useQuery({ queryKey: ['builds', id], queryFn: () => api.builds(id) })
  const last = builds.data?.items.find((b) => b.status === 'succeeded' && !b.deleted)
  const docs = Object.keys(preview.data?.docs ?? {})
  const [tab, setTab] = useState('docs/INSTALL.md')
  const shownTab = tab === 'contents' || tab === 'pipeline' || docs.includes(tab) ? tab : docs[0] ?? 'contents'
  const formats = new Map((preview.data?.formats ?? []).map((f) => [f.id, f.file]))
  const agentDocs = docs.includes('AGENTS.md')
  const root = version.data?.root ?? `${id}.mcpgp.yaml`

  return (
    <>
      <PageHead title="Packaging"
        sub={base > 0 ? `What a build of version ${base} contains, and how your customer installs it.` : undefined}>
        {last && <Link to={`/servers/${id}/builds?build=${last.id}`} className="btn">Last build {last.serverVersion}</Link>}
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Package settings">
          <ErrorBox error={server.error ?? preview.error} />
          {preview.error instanceof ApiError && preview.error.status === 422 && (
            <p className="hint">The latest version doesn't compile; the checks on <Link to={`/servers/${id}/apis`}>API definitions</Link> say why.</p>
          )}
          {preview.isLoading && <Loading what="the package" />}
          {preview.data && (
            <>
              <div className="card">
                <h2>Formats</h2>
                <div className="rows">
                  {FORMATS.map((f) => (
                    <div key={f.id} className="row" style={{ background: 'none', padding: '10px 0', alignItems: 'flex-start' }}>
                      <span style={{ flex: 1, minWidth: 0 }}>
                        <b style={{ fontSize: 14, fontWeight: 600 }}>{f.title}</b><br />
                        <span className="hint">{f.use}</span>
                        {formats.get(f.id) && <><br /><span className="mono hint">{formats.get(f.id)}</span></>}
                      </span>
                      <span className="tag">{formats.has(f.id) ? 'in the package' : 'not built'}</span>
                    </div>
                  ))}
                </div>
                <p className="hint">Choose formats with <span className="mono">build.targets</span> in the definition (on{' '}
                  <Link to={`/servers/${id}/apis`}>API definitions</Link>).</p>
              </div>
              <div className="card">
                <h2>How it runs</h2>
                <dl className="kv">
                  <dt>Generation</dt><dd>Runtime: the server works from its manifest; nothing is generated as code</dd>
                  <dt>Transports</dt><dd>{manifest.data?.runtime.transports?.allowed.join(', ') ?? '—'}{manifest.data?.runtime.transports?.default && ` (default ${manifest.data.runtime.transports.default})`}</dd>
                  <dt>Packs</dt><dd>{manifest.data?.packs.length ? manifest.data.packs.map((p) => p.id).join(', ') : 'none'}</dd>
                  <dt>Signing</dt><dd>{preview.data.signed ? 'Signed: SHA256SUMS.sig and the public key ship with it' : 'Not signed: the platform has no signing key'}</dd>
                  <dt>Docs</dt><dd>{docs.length} files{agentDocs ? ', including docs for agents' : <>; add docs for agents with <span className="mono">build.docs: [agents]</span></>}</dd>
                </dl>
              </div>
              {preview.data.warnings.length > 0 && (
                <div className="note note-warn">
                  {preview.data.warnings.map((w) => <div key={w}>{w}</div>)}
                </div>
              )}
              <div className="note note-ok">Works fully offline: no license call, no telemetry to you, no downloads at runtime.</div>
            </>
          )}
        </section>
        <section className="stage" aria-label="What ships">
          {preview.data && (
            <>
              <div className="card-head">
                <Segmented role="tab" label="Show" value={shownTab} onChange={setTab}
                  options={[...docs.map((d) => [d, DOC_LABELS[d] ?? d] as [string, string]), ['contents', 'Contents'], ['pipeline', 'Pipeline']]} />
              </div>
              {docs.includes(shownTab) && (
                <>
                  <div className="eyebrow">{shownTab} · as it ships</div>
                  {shownTab.endsWith('.md') ? <Markdown text={preview.data.docs[shownTab]} open={(href) => {
                    const target = docs.find((d) => d === href || d.endsWith('/' + href.replace(/^\.\//, '')) || d === href.replace(/^\.\.\//, ''))
                    return target ? () => setTab(target) : undefined
                  }} /> : <pre className="code">{preview.data.docs[shownTab]}</pre>}
                </>
              )}
              {shownTab === 'contents' && (
                <>
                  <div className="eyebrow">{preview.data.zip.replace(/\.zip$/, '')}….zip</div>
                  <pre className="code" aria-label="Package contents">{preview.data.files.join('\n')}</pre>
                  <p className="hint">Every file is listed in SHA256SUMS{preview.data.signed ? ', which is signed' : ''}. The build adds its number to the version.</p>
                </>
              )}
              {shownTab === 'pipeline' && (
                <>
                  <div className="eyebrow">The same build in a pipeline</div>
                  <pre className="code">{[`mcpgp validate ${root}`, `mcpgp test ${root}`, `mcpgp build ${root} --frozen-lock`].join('\n')}</pre>
                  <p className="hint">Or let the platform build it: push the definition from CI with a pipeline token.</p>
                  <pre className="code">{`MCPGP_PLATFORM=${window.location.origin} MCPGP_TOKEN=… \\\n  mcpgp push ${root} --build --verify-local`}</pre>
                </>
              )}
            </>
          )}
        </section>
      </div>
    </>
  )
}
