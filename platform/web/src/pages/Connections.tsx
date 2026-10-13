import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type Manifest } from '../api'
import { AUTH_LABELS, useManifest, useServer } from '../serverData'
import { useSession } from '../session'
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
          {manifest.data && <Settings id={id} manifest={manifest.data} />}
        </section>
        <section className="stage" aria-label="Customer preview">
          {manifest.data && <CustomerPreview id={id} />}
          {manifest.data && <InstanceNote manifest={manifest.data} />}
        </section>
      </div>
    </>
  )
}

function Settings({ id, manifest }: { id: string; manifest: Manifest }) {
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
              <FromRequest id={id} api={a.id} server={manifest.server.name} several={manifest.apis.length > 1 || multi}
                current={a.downstreamAuth.fromRequest} />
              <TokenExchangeSwitch id={id} api={a.id} on={!!a.downstreamAuth.tokenExchange} />
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

/** The X-<Server>-Authorization default the runtime uses (auth.md §2.4). */
export function defaultCredentialHeader(server: string): string {
  return 'X-' + server.split(/[^A-Za-z0-9]+/).filter(Boolean).map((w) => w[0].toUpperCase() + w.slice(1).toLowerCase()).join('-') + '-Authorization'
}

/** Whether customers' MCP clients may send their own credential for the API in a request header. */
function FromRequest({ id, api: apiId, server, several, current }: {
  id: string; api: string; server: string; several: boolean; current?: { header?: string }
}) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const serverData = useServer(id)
  const [header, setHeader] = useState(current?.header ?? '')
  const enabled = !!current
  const editable = can('servers:write') && !serverData.data?.archived
  const save = useMutation({
    mutationFn: (on: boolean) => api.saveEdit(id, serverData.data?.latestVersion ?? 0, [on
      ? { op: 'set', path: `/apis/[id=${apiId}]/downstreamAuth/fromRequest`, value: header.trim() ? { header: header.trim() } : {} }
      : { op: 'remove', path: `/apis/[id=${apiId}]/downstreamAuth/fromRequest` }],
      on ? 'Clients may send their own credential' + (header.trim() ? ' in ' + header.trim() : '') : 'Clients no longer send their own credential'),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const valid = !header.trim() || /^[A-Za-z0-9][A-Za-z0-9-]{0,63}$/.test(header.trim())
  const shown = header.trim() || defaultCredentialHeader(server) + (several ? ' (plus the API or server id)' : '')
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>The user's own credential</h2>
      <p className="hint">
        The MCP client can send its user's credential for the API in a request header, set in the client's configuration: the agent never
        sees it, and the server uses it for that call only, never storing or logging it. A credential configured for the target server is
        used when the header is missing. Over HTTP only.
      </p>
      <dl className="kv">
        <dt>Allowed</dt><dd>{enabled ? `Yes, in ${shown}` : 'No'}</dd>
      </dl>
      {editable && (
        <form className="actions" onSubmit={(e) => { e.preventDefault(); save.mutate(true) }}>
          <label className="field" style={{ flex: '1 1 240px' }}><span className="visually-hidden">Header</span>
            <input className="input mono" value={header} placeholder={defaultCredentialHeader(server)} aria-invalid={!valid}
              onChange={(e) => setHeader(e.target.value)} />
          </label>
          <button type="submit" className="btn btn-sm btn-primary" disabled={!valid || save.isPending}>{enabled ? 'Save header' : 'Allow'}</button>
          {enabled && <button type="button" className="btn btn-sm" disabled={save.isPending} onClick={() => save.mutate(false)}>Turn off</button>}
        </form>
      )}
      <ErrorBox error={save.error} />
    </div>
  )
}

/** Whether instances may call the API as the signed-in user, their OAuth sign-in exchanged at the identity provider. */
function TokenExchangeSwitch({ id, api: apiId, on }: { id: string; api: string; on: boolean }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const serverData = useServer(id)
  const editable = can('servers:write') && !serverData.data?.archived
  const save = useMutation({
    mutationFn: (enable: boolean) => api.saveEdit(id, serverData.data?.latestVersion ?? 0, [enable
      ? { op: 'set', path: `/apis/[id=${apiId}]/downstreamAuth/tokenExchange`, value: {} }
      : { op: 'remove', path: `/apis/[id=${apiId}]/downstreamAuth/tokenExchange` }],
      enable ? 'Instances may call the API as the signed-in user' : 'Instances no longer call the API as the signed-in user'),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>The signed-in user's identity</h2>
      <p className="hint">
        For a shared server whose users sign in with your identity provider (OAuth): every call exchanges the user's sign-in for a token
        issued for the API (token exchange, or Microsoft Entra on-behalf-of), so the API sees who is calling. The user's own token goes
        only to your identity provider. Whoever runs the server configures the exchange.
      </p>
      <dl className="kv"><dt>Allowed</dt><dd>{on ? 'Yes' : 'No'}</dd></dl>
      {editable && (
        <div className="actions">
          <button type="button" className={'btn btn-sm' + (on ? '' : ' btn-primary')} disabled={save.isPending} onClick={() => save.mutate(!on)}>
            {on ? 'Turn off' : 'Allow'}</button>
        </div>
      )}
      <ErrorBox error={save.error} />
    </div>
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
