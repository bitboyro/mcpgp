import { useParams } from 'react-router-dom'
import type { Manifest } from '../api'
import { AUTH_LABELS, useManifest } from '../serverData'
import { ErrorBox, Loading, PageHead } from '../ui'
import { BuildButton, CustomerPreview } from './Overview'

const MIXED: Record<string, string> = {
  union: 'One tool, checked per call',
  namespaced: 'Separate tools per server',
  forbid: 'Same version only',
}

export function ConnectionsPage() {
  const { id = '' } = useParams()
  const manifest = useManifest(id)
  return (
    <>
      <PageHead title="Connections" sub="Which API versions you ship, and what your customers can connect to.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={manifest.error} />
          {manifest.isLoading && <Loading />}
          {manifest.data && <Settings manifest={manifest.data} />}
        </section>
        <section className="stage" aria-label="Customer preview">
          {manifest.data && <CustomerPreview id={id} />}
          {manifest.data && <InstanceNote manifest={manifest.data} />}
        </section>
      </div>
    </>
  )
}

function Settings({ manifest }: { manifest: Manifest }) {
  const multi = manifest.instances.mode === 'multi'
  return (
    <>
      {manifest.apis.map((a) => (
        <div key={a.id} className="card">
          <div className="card-head">
            <h2>{a.title ?? a.id}: contracts shipped</h2>
            <span className="tag mono">{a.toolPrefix ?? a.id}_*</span>
          </div>
          {a.contracts.map((c) => (
            <div key={c.version} className="row">
              <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <b style={{ fontSize: 20, letterSpacing: '-0.02em' }}>{c.version}</b>
                <span><span style={{ fontSize: 13, fontWeight: 500 }}>{c.format}</span><br /><span className="meta">{c.basePath ?? '/'}{c.default ? ' · default' : ''}</span></span>
              </span>
              {a.detect && <span className="meta mono">{a.detect.method} {a.detect.path}</span>}
            </div>
          ))}
          {a.downstreamAuth && (
            <>
              <h2 style={{ marginTop: 4 }}>Sign-in types customers can use</h2>
              <div className="actions">
                {a.downstreamAuth.supported.map((s) => <span key={s} className="pill" aria-pressed="true">{AUTH_LABELS[s] ?? s}</span>)}
              </div>
              <dl className="kv">
                <dt>Whose identity</dt><dd>{a.downstreamAuth.identity === 'service-account' ? 'A service account per target server' : a.downstreamAuth.identity}</dd>
                <dt>A tool the sign-in can't use</dt><dd>{a.downstreamAuth.unsatisfiedTools === 'hide' ? 'Hidden' : 'Kept, with a clear error'}</dd>
                {a.downstreamAuth.apiKey && <><dt>API key</dt><dd className="mono">{a.downstreamAuth.apiKey.in}: {a.downstreamAuth.apiKey.name}</dd></>}
              </dl>
            </>
          )}
        </div>
      ))}
      <div className="card">
        <h2>Target servers</h2>
        <dl className="kv">
          <dt>How many</dt><dd>{multi ? 'Several; agents choose one per call' : 'One'}</dd>
          {multi && <><dt>Different versions</dt><dd>{MIXED[manifest.instances.mixedContracts ?? 'union'] ?? manifest.instances.mixedContracts}</dd></>}
          {manifest.instances.max != null && <><dt>At most</dt><dd>{manifest.instances.max}</dd></>}
        </dl>
        <p className="hint">These come from the definition's <span className="mono">instances</span> and <span className="mono">apis</span> sections.</p>
      </div>
    </>
  )
}

function InstanceNote({ manifest }: { manifest: Manifest }) {
  if (manifest.instances.mode !== 'multi') {
    return <div className="note note-ok">One server: tool inputs only contain the API’s own parameters.</div>
  }
  const mixed = manifest.instances.mixedContracts ?? 'union'
  return (
    <div className={'note ' + (mixed === 'namespaced' ? 'note-warn' : '')}>
      {mixed === 'union' && 'Every tool takes an "instance" input, filled from the customer’s list. One tool serves every version; a field a server’s version lacks returns a clear error.'}
      {mixed === 'namespaced' && 'Exact schema per server, but tools repeat for every target server.'}
      {mixed === 'forbid' && 'All target servers must run the same contract version, or the server refuses to start.'}
    </div>
  )
}
