import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type Detect, type Manifest } from '../api'
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
            </div>
          ))}
          {a.contracts.length > 1 && <VersionDetection id={id} api={a.id} current={a.detect} />}
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
  const editable = can('servers:write') && !serverData.data?.archived && !serverData.data?.system
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

type Where = 'field' | 'header' | 'body'

/**
 * How a target server says which version it runs, for customers' instances with `contract: auto`
 * (runtime-config §4.4): the definition's `apis[].detect`. Only with two or more versions shipped.
 */
function VersionDetection({ id, api: apiId, current }: { id: string; api: string; current?: Detect }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const serverData = useServer(id)
  const editable = can('servers:write') && !serverData.data?.archived && !serverData.data?.system
  const [path, setPath] = useState(current?.path ?? '/version')
  const [under, setUnder] = useState(current ? !!current.underBasePath : true)
  const [where, setWhere] = useState<Where>(current?.header ? 'header' : current?.field || !current ? 'field' : 'body')
  const [field, setField] = useState(current?.field ?? '/version')
  const [header, setHeader] = useState(current?.header ?? 'X-Api-Version')
  const [pattern, setPattern] = useState(current?.pattern ?? '^(\\d+\\.\\d+)')
  const [refuse, setRefuse] = useState(current?.onFailure === 'refuse')
  const save = useMutation({
    mutationFn: (on: boolean) => {
      const rule: Detect = { method: current?.method ?? 'GET', path: path.trim(), pattern }
      if (under) rule.underBasePath = true
      if (where === 'field') rule.field = field.trim()
      if (where === 'header') rule.header = header.trim()
      if (refuse) rule.onFailure = 'refuse'
      return api.saveEdit(id, serverData.data?.latestVersion ?? 0, [on
        ? { op: 'set', path: `/apis/[id=${apiId}]/detect`, value: rule }
        : { op: 'remove', path: `/apis/[id=${apiId}]/detect` }],
        on ? 'Version detection: ' + rule.method + ' ' + rule.path : 'Version detection turned off')
    },
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const problem = !path.trim().startsWith('/') ? 'The path starts with /'
    : where === 'field' && !field.trim().startsWith('/') ? 'The field is a JSON pointer, starting with / (/version)'
    : where === 'header' && !/^[A-Za-z0-9-]+$/.test(header.trim()) ? 'The header is a name like X-Api-Version'
    : !validPattern(pattern) ? 'The pattern isn’t a valid regular expression' : null
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>Which version a customer’s server runs</h2>
      <p className="hint">
        When your customer leaves the version on automatic, the MCP server asks their system which version it runs, once when it starts.
        Without this, it uses the default version for every system. The answer is read here; it is never checked from the platform.
      </p>
      <dl className="kv">
        <dt>Asked</dt><dd>{current
          ? <span className="mono">{current.method} {current.path}{current.underBasePath ? ' under each version’s base path' : ''}</span>
          : 'No: the default version is used'}</dd>
      </dl>
      {editable && (
        <form className="list" style={{ gap: 8 }} onSubmit={(e) => { e.preventDefault(); save.mutate(true) }}>
          <label className="field"><span>Path that answers with the version</span>
            <input className="input mono" value={path} onChange={(e) => setPath(e.target.value)} />
          </label>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}><input type="checkbox" checked={under} onChange={(e) => setUnder(e.target.checked)} />
            Each version answers under its own base path (<span className="mono">/api/v2/version</span>, <span className="mono">/api/v1/version</span>)</label>
          <label className="field"><span>Where the version is in the answer</span>
            <select className="input" value={where} onChange={(e) => setWhere(e.target.value as Where)}>
              <option value="field">A field in the JSON answer</option>
              <option value="header">A response header</option>
              <option value="body">The whole answer</option>
            </select>
          </label>
          {where === 'field' && <label className="field"><span>Field (JSON pointer)</span>
            <input className="input mono" value={field} onChange={(e) => setField(e.target.value)} /></label>}
          {where === 'header' && <label className="field"><span>Header</span>
            <input className="input mono" value={header} onChange={(e) => setHeader(e.target.value)} /></label>}
          <label className="field"><span>Pattern (its first group is the version)</span>
            <input className="input mono" value={pattern} onChange={(e) => setPattern(e.target.value)} />
          </label>
          <label className="field"><span>If the version can’t be found</span>
            <select className="input" value={refuse ? 'refuse' : 'useDefault'} onChange={(e) => setRefuse(e.target.value === 'refuse')}>
              <option value="useDefault">Use the default version</option>
              <option value="refuse">Refuse calls to that server until it can</option>
            </select>
          </label>
          {problem && <p className="hint" role="alert">{problem}</p>}
          <div className="actions">
            <button type="submit" className="btn btn-sm btn-primary" disabled={!!problem || save.isPending}>{current ? 'Save' : 'Turn on'}</button>
            {current && <button type="button" className="btn btn-sm" disabled={save.isPending} onClick={() => save.mutate(false)}>Turn off</button>}
          </div>
        </form>
      )}
      <ErrorBox error={save.error} />
    </div>
  )
}

function validPattern(pattern: string) {
  try {
    new RegExp(pattern)
    return pattern.length > 0
  } catch {
    return false
  }
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
