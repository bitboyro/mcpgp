import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { Change, Manifest } from '../api'
import { useManifest } from '../serverData'
import { ErrorBox, Loading, PageHead, Icon } from '../ui'
import { SetWhenDeployed, useDefinitionSave } from './Deployed'
import { Toggle } from './Execution'
import { BuildButton, CustomerPreview } from './Overview'

const METHODS: [string, string, string][] = [
  ['none', 'No sign-in', 'Anyone who can reach the server. Local (stdio) use, or HTTP on a trusted network.'],
  ['static', 'API keys', 'Each client sends a key the operator issues.'],
  ['oauth', 'OAuth', 'Clients sign in with your identity provider; the server checks their tokens.'],
]

const ACCESS: [string, string][] = [['read-only', 'Read only'], ['read-write', 'Read and write (no deletes)'], ['all', 'Everything']]

interface ScopeRow { scope: string; access: string; tools: string }

interface Settings { allowed: string[]; method: string; sessions: boolean; scopes: ScopeRow[] }

function current(m: Manifest): Settings {
  const scopes = m.policies.runtimeDefaults?.configuration?.server?.auth?.scopes ?? {}
  return {
    allowed: m.runtime.inboundAuth?.allowed ?? ['none', 'static', 'oauth'],
    method: m.runtime.inboundAuth?.default ?? 'none',
    sessions: m.policies.runtimeDefaults?.configuration?.sessions?.enabled ?? false,
    scopes: Object.entries(scopes).map(([scope, s]) => ({ scope, access: s.access ?? '', tools: (s.tools ?? []).join(', ') })),
  }
}

function scopesValue(rows: ScopeRow[]): Record<string, { access?: string; tools?: string[] }> {
  const value: Record<string, { access?: string; tools?: string[] }> = {}
  for (const r of rows) {
    const tools = r.tools.split(',').map((t) => t.trim()).filter(Boolean)
    value[r.scope.trim()] = { ...(r.access ? { access: r.access } : {}), ...(tools.length ? { tools } : {}) }
  }
  return value
}

/**
 * Access control: who may connect and what they may do. The package's defaults and limits are edited here
 * (the definition's runtimeDefaults); everything a deployment supplies is shown, not edited (design/ui.md).
 */
export function AccessControlPage() {
  const { id = '' } = useParams()
  const manifest = useManifest(id)
  return (
    <>
      <PageHead title="Access control" sub="Who can connect to the server, and what they may do.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={manifest.error} />
          {manifest.isLoading && <Loading />}
          {manifest.data && <AccessForm id={id} saved={current(manifest.data)} />}
          <SetWhenDeployed title="Sign-in details" items={[
            { label: 'Method in use', key: 'server.auth.mode', hint: 'Within the methods offered above.' },
            { label: 'API keys', key: 'server.auth.keys', hint: 'Secrets, as references such as ${env:…}.' },
            { label: 'OAuth', key: 'server.auth.issuer · jwksUri · introspection', hint: 'Your identity provider.' },
            { label: 'Plain HTTP without sign-in', key: 'server.auth.allowInsecure' },
            { label: 'Browser origins', key: 'server.allowedOrigins' },
          ]} />
          <SetWhenDeployed title="Restrictions where the server runs" items={[
            { label: 'Tools allowed and denied', key: 'tools · apis.<api>.tools · instances[].tools', hint: 'Narrow what this package ships; deny wins. What gets built at all is on Tools → Operations.' },
            { label: 'Rules per user or scope', key: '--identity-policy <file>', hint: 'A file an administrator mounts: limits per signed-in subject or scope, and which target servers each may use.' },
            { label: 'Organization policy', key: '--organization-policy <file>', hint: 'A file an administrator mounts: shared defaults and limits no deployment can loosen.' },
            { label: 'Policy management API', key: 'server.management', hint: 'Off unless the operator turns it on, with its own administrator keys.' },
          ]} />
        </section>
        <section className="stage" aria-label="Customer preview">
          {manifest.data && <CustomerPreview id={id} />}
        </section>
      </div>
    </>
  )
}

function AccessForm({ id, saved }: { id: string; saved: Settings }) {
  const { editable, save } = useDefinitionSave(id, 'Access control')
  const [s, setS] = useState(saved)
  useEffect(() => setS(saved), [JSON.stringify(saved)]) // eslint-disable-line react-hooks/exhaustive-deps
  const off = !editable

  const changes: Change[] = []
  const what: string[] = []
  if (JSON.stringify(s.allowed) !== JSON.stringify(saved.allowed) || s.method !== saved.method) {
    changes.push({ op: 'set', path: '/runtimeDefaults/inboundAuth', value: { allowed: s.allowed, default: s.method } })
    what.push('sign-in methods')
  }
  if (s.sessions !== saved.sessions) {
    changes.push({ op: 'set', path: '/runtimeDefaults/configuration/sessions/enabled', value: s.sessions })
    what.push(s.sessions ? 'sessions on' : 'sessions off')
  }
  if (JSON.stringify(s.scopes) !== JSON.stringify(saved.scopes)) {
    changes.push(s.scopes.length
      ? { op: 'set', path: '/runtimeDefaults/configuration/server/auth/scopes', value: scopesValue(s.scopes) }
      : { op: 'remove', path: '/runtimeDefaults/configuration/server/auth/scopes' })
    what.push('scope permissions')
  }
  const names = s.scopes.map((r) => r.scope.trim())
  const problem = s.allowed.length === 0 ? 'Offer at least one sign-in method'
    : !s.allowed.includes(s.method) ? 'The default method must be one of those offered'
      : names.some((n) => !n) ? 'Every scope needs a name'
        : new Set(names).size !== names.length ? 'Each scope appears once'
          : s.scopes.some((r) => !r.access && !r.tools.trim()) ? 'Give each scope an access level or tools' : null

  const toggleMethod = (m: string, on: boolean) => setS((old) => ({ ...old, allowed: METHODS.map(([k]) => k).filter((k) => k === m ? on : old.allowed.includes(k)) }))
  const setRow = (i: number, row: Partial<ScopeRow>) => setS((old) => ({ ...old, scopes: old.scopes.map((r, n) => n === i ? { ...r, ...row } : r) }))

  return (
    <form className="list" style={{ gap: 16 }} onSubmit={(e) => { e.preventDefault(); save.mutate({ changes, what: what.join(', ') }) }}>
      <div className="card">
        <h2>Client sign-in</h2>
        <p className="hint">The methods this package offers. Whoever runs it picks one of these, and can't use another.</p>
        {METHODS.map(([key, label, hint]) => (
          <Toggle key={key} label={label} checked={s.allowed.includes(key)} disabled={off} onChange={(v) => toggleMethod(key, v)} hint={hint} />
        ))}
        <label className="field"><span>Used unless the operator picks another</span>
          <select className="input" value={s.method} disabled={off} onChange={(e) => setS({ ...s, method: e.target.value })} style={{ maxWidth: 240 }}>
            {METHODS.filter(([k]) => s.allowed.includes(k)).map(([k, label]) => <option key={k} value={k}>{label}</option>)}
          </select>
        </label>
      </div>

      <div className="card">
        <h2>Permissions by OAuth scope</h2>
        <p className="hint">With OAuth, a token's scopes decide which tools it can list and call. These ship as defaults; the operator can change
          them to match their identity provider. Without any, every signed-in client gets every tool.</p>
        {s.scopes.map((r, i) => (
          <div key={i} className="actions" style={{ alignItems: 'flex-end' }}>
            <label className="field" style={{ flex: '1 1 140px' }}><span>Scope</span>
              <input className="input mono" value={r.scope} disabled={off} placeholder="orders.read" onChange={(e) => setRow(i, { scope: e.target.value })} />
            </label>
            <label className="field" style={{ flex: '1 1 160px' }}><span>Access</span>
              <select className="input" value={r.access} disabled={off} onChange={(e) => setRow(i, { access: e.target.value })}>
                <option value="">Only the tools listed</option>
                {ACCESS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
              </select>
            </label>
            <label className="field" style={{ flex: '2 1 200px' }}><span>Tools (optional, * as a wildcard)</span>
              <input className="input mono" value={r.tools} disabled={off} placeholder="orders_order_*" onChange={(e) => setRow(i, { tools: e.target.value })} />
            </label>
            {!off && <button type="button" className="btn btn-sm" aria-label={`Remove scope ${r.scope || i + 1}`}
              onClick={() => setS({ ...s, scopes: s.scopes.filter((_, n) => n !== i) })}><Icon name="trash" />Remove</button>}
          </div>
        ))}
        {!off && <div><button type="button" className="btn btn-sm" onClick={() => setS({ ...s, scopes: [...s.scopes, { scope: '', access: 'read-only', tools: '' }] })}><Icon name="plus" />Add scope</button></div>}
      </div>

      <div className="card">
        <h2>Sessions</h2>
        <Toggle label="Let clients narrow their own access for a session" checked={s.sessions} disabled={off} onChange={(v) => setS({ ...s, sessions: v })}
          hint="Adds the session_policy tool: a client can restrict the tools and target servers it uses until it disconnects, never widen them. A default the operator can change." />
      </div>

      {editable && (
        <div className="actions">
          <button type="submit" className="btn btn-primary" disabled={changes.length === 0 || !!problem || save.isPending}><Icon name="save" />Save as the next version</button>
          {changes.length > 0 && <button type="button" className="btn" onClick={() => setS(saved)}><Icon name="undo" />Undo changes</button>}
          {problem && changes.length > 0 && <span className="hint" role="alert">{problem}</span>}
        </div>
      )}
      <ErrorBox error={save.error} />
      <p className="hint">Organization and identity policies, where an administrator mounts them, can narrow all of this further. See
        {' '}<Link to={`/servers/${id}/execution`}>Execution</Link> for confirmations and limits.</p>
    </form>
  )
}
