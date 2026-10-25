import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type Manifest } from '../api'
import { AUTH_LABELS, useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, Help } from '../ui'
import { BuildButton, CustomerPreview } from './Overview'
import { ChoiceRows, SettingsCard, usePendingEdits } from './SettingsCard'

/** Target APIs: the servers a customer's MCP server calls, and how it signs in to them. */
export function TargetApisPage() {
  const { id = '' } = useParams()
  const manifest = useManifest(id)
  return (
    <>
      <PageHead title="Target APIs" sub="The APIs your server calls, and how it signs in to them.">
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
  const edits = usePendingEdits(id, 'Target APIs')
  return (
    <>
      <SettingsCard id={id} title="Target servers" edits={edits}>
        {(m) => <>
          <ChoiceRows manifest={m} only={(c) => c === 'instances' || c === 'mixed'} edits={edits} />
          {m.instances.max != null && <dl className="kv"><dt>At most</dt><dd>{m.instances.max}</dd></dl>}
        </>}
      </SettingsCard>
      {manifest.apis.map((a) => (
        <div key={a.id} className="card">
          <div className="card-head">
            <h2>{a.title ?? a.id}: sign-in</h2>
            <span className="tag mono">{a.toolPrefix ?? a.id}_*</span>
          </div>
          {a.upstreamAuth ? (
            <>
              <h2 style={{ marginTop: 4 }}>Sign-in types customers can use</h2>
              <div className="actions">
                {a.upstreamAuth.supported.map((s) => <span key={s} className="pill" aria-pressed="true">{AUTH_LABELS[s] ?? s}</span>)}
              </div>
              <dl className="kv">
                <dt>Whose identity</dt><dd>{a.upstreamAuth.identity === 'service-account' ? 'A service account per target server' : a.upstreamAuth.identity}</dd>
                <dt>A tool the sign-in can't use</dt><dd>{a.upstreamAuth.unsatisfiedTools === 'hide' ? 'Hidden' : 'Kept, with a clear error'}</dd>
                {a.upstreamAuth.apiKey && <><dt>API key</dt><dd className="mono">{a.upstreamAuth.apiKey.in}: {a.upstreamAuth.apiKey.name}</dd></>}
              </dl>
              <FromRequest id={id} api={a.id} server={manifest.server.name} several={manifest.apis.length > 1 || multi}
                current={a.upstreamAuth.fromRequest} />
              <TokenExchangeSwitch id={id} api={a.id} on={!!a.upstreamAuth.tokenExchange} />
            </>
          ) : <p className="hint">The API's spec declares no sign-in.</p>}
        </div>
      ))}
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
  const editable = can('servers:write') && !serverData.data?.archived && !serverData.data?.system
  const save = useMutation({
    mutationFn: (on: boolean) => api.saveEdit(id, serverData.data?.latestVersion ?? 0, [on
      ? { op: 'set', path: `/apis/[id=${apiId}]/upstreamAuth/fromRequest`, value: header.trim() ? { header: header.trim() } : {} }
      : { op: 'remove', path: `/apis/[id=${apiId}]/upstreamAuth/fromRequest` }],
      on ? 'Clients may send their own credential' + (header.trim() ? ' in ' + header.trim() : '') : 'Clients no longer send their own credential'),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const valid = !header.trim() || /^[A-Za-z0-9][A-Za-z0-9-]{0,63}$/.test(header.trim())
  const shown = header.trim() || defaultCredentialHeader(server) + (several ? ' (plus the API or server id)' : '')
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>Per-user credential</h2>
      <Help text="Each user's own API credential, sent by their MCP client. Never stored."
        more="The client sends it in a request header set in its configuration; the agent never sees it, and the server uses it for that call only, never storing or logging it. A credential configured for the target server is used when the header is missing. Over HTTP only." />
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
      ? { op: 'set', path: `/apis/[id=${apiId}]/upstreamAuth/tokenExchange`, value: {} }
      : { op: 'remove', path: `/apis/[id=${apiId}]/upstreamAuth/tokenExchange` }],
      enable ? 'Instances may call the API as the signed-in user' : 'Instances no longer call the API as the signed-in user'),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>Act as the signed-in user</h2>
      <Help text="Calls the API as the signed-in user, through OAuth token exchange."
        more="For a shared server whose users sign in with your identity provider: every call exchanges the user's sign-in for a token issued for the API (token exchange, or Microsoft Entra on-behalf-of), so the API sees who is calling. The user's own token goes only to your identity provider. Whoever runs the server configures the exchange." />
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
      {mixed === 'forbid' && 'All target servers must run the same API version, or the server refuses to start.'}
    </div>
  )
}
