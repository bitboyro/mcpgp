import { Link, useParams } from 'react-router-dom'
import { useManifest, useServer } from '../serverData'
import { ErrorBox, Loading, PageHead, ago } from '../ui'
import { BuildButton } from './Overview'

/** General: what the MCP server is, as connecting agents see it in initialize, and where its definition stands. */
export function GeneralPage() {
  const { id = '' } = useParams()
  const server = useServer(id)
  const manifest = useManifest(id)
  const latest = server.data?.versions.at(-1)
  return (
    <>
      <PageHead title="General" sub="What this MCP server is, and what agents are told when they connect.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={server.error ?? manifest.error} />
          {(server.isLoading || manifest.isLoading) && <Loading />}
          {manifest.data && (
            <div className="card">
              <h2>Identity</h2>
              <dl className="kv">
                <dt>Name</dt><dd className="mono">{manifest.data.server.name}</dd>
                <dt>Title</dt><dd>{manifest.data.server.title ?? '—'}</dd>
                <dt>Server version</dt><dd className="mono">{manifest.data.server.version}</dd>
              </dl>
              <p className="hint">Sent to agents as the server's info when they connect.</p>
            </div>
          )}
          {manifest.data && (
            <div className="card">
              <h2>Instructions for agents</h2>
              {manifest.data.server.instructions
                ? <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{manifest.data.server.instructions}</p>
                : <p className="hint">None. The definition's description becomes these instructions.</p>}
              <p className="hint">Sent in the initialize response; clients decide how to use them. Tool descriptions and notices are on <Link to={`/servers/${id}/tools`}>Tools</Link>.</p>
            </div>
          )}
          {server.data && (
            <div className="card">
              <h2>Definition</h2>
              <dl className="kv">
                <dt>Server</dt><dd className="mono">{manifest.data?.server.name ?? id} {manifest.data?.server.version}</dd>
                <dt>Revision</dt><dd>{latest ? <>{latest.number} · by {latest.author} {ago(latest.created)}{latest.message ? ` · ${latest.message}` : ''}</> : 'none yet'}</dd>
                {server.data.parent && <><dt>Variant of</dt><dd><Link to={`/servers/${server.data.parent}`}>{server.data.parent}</Link></dd></>}
                {manifest.data?.build.extends && <><dt>Extends</dt><dd className="mono">{manifest.data.build.extends.name}{manifest.data.build.extends.version ? '@' + manifest.data.build.extends.version : ''}</dd></>}
              </dl>
              <p className="hint">Each saved change becomes a new revision, like an upload, with your name in Activity. Comments and layout in the file stay as they are.</p>
            </div>
          )}
        </section>
      </div>
    </>
  )
}
