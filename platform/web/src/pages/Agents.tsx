import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type Identity, type Token } from '../api'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead, Segmented, ago } from '../ui'
import { Approvals, describe } from './Activity'
import { ConnectAgent } from './ConnectAgent'
import { PersonPassword } from './Passwords'

const ROLES: [string, string][] = [['viewer', 'Viewer'], ['builder', 'Builder'], ['releaser', 'Releaser'], ['admin', 'Admin']]

export function AgentsPage() {
  const { me, settings, can } = useSession()
  const admin = can('admin')
  const identities = useQuery({ queryKey: ['identities'], queryFn: api.identities, enabled: admin })
  const tokens = useQuery({ queryKey: ['tokens'], queryFn: api.tokens })
  const agents = useQuery({ queryKey: ['activity', 'agents'], queryFn: () => api.activity({ limit: 200 }) })
  const [issued, setIssued] = useState<Token | null>(null)
  const [connecting, setConnecting] = useState(false)
  const people = admin ? identities.data?.items ?? [] : [me]
  const agentActivity = (agents.data?.items ?? []).filter((a) => a.actorKind === 'agent' || a.actorKind === 'pipeline').slice(0, 12)

  return (
    <>
      <PageHead title="API & agents" sub="People, pipelines and agents use the same API. Agents can build their own MCP servers, within your guardrails." />
      <div className="body">
        <section className="col" aria-label="Identities">
          {issued && <IssuedToken token={issued} onClose={() => setIssued(null)} />}
          {admin && <NewIdentity onIssued={setIssued} />}
          <div className="card">
            <h2>Identities</h2>
            <ErrorBox error={identities.error ?? tokens.error} />
            {identities.isLoading && <Loading />}
            <div className="rows">
              {people.map((identity) => (
                <IdentityRow key={identity.id} identity={identity} tokens={(tokens.data?.items ?? []).filter((t) => t.identity === identity.id)}
                  onIssued={setIssued} canIssue={admin || identity.id === me.id} admin={admin} />
              ))}
            </div>
          </div>
          <div className="card">
            <h2>Guardrails for agents</h2>
            <dl className="kv">
              <dt>Builds per hour</dt><dd>{settings.guardrails.agentBuildsPerHour} per agent</dd>
              <dt>Versions per hour</dt><dd>{settings.guardrails.agentVersionsPerHour} per agent (new servers count)</dd>
              <dt>Releases</dt><dd>need a person's approval</dd>
              <dt>Never</dt><dd>delete builds, change identities or settings</dd>
            </dl>
            <p className="hint">Set in <span className="mono">platform.yaml</span> under <span className="mono">guardrails</span>.</p>
          </div>
        </section>
        <section className="stage" aria-label="API">
          <div className="stage-card">
            <span className="eyebrow">REST API</span>
            <h3 className="mono">{window.location.origin}/api/v1</h3>
            <span className="meta">Bearer tokens · described by <a href="/api/v1/openapi.json">its OpenAPI document</a></span>
            <pre className="code code-wrap" style={{ border: 0, padding: 0 }}>{`curl -H "Authorization: Bearer $MCPGP_TOKEN" \\\n  ${window.location.origin}/api/v1/servers`}</pre>
          </div>
          {connecting ? <ConnectAgent onClose={() => setConnecting(false)} /> : <div className="stage-card">
            <span className="eyebrow">Platform MCP server</span>
            <p>Built by mcpgp from this API's OpenAPI document. Agents run it themselves with their own token, and get the platform as tools; the
              same scopes, quotas and approvals apply.</p>
            <div className="actions">
              <button type="button" className="btn btn-primary btn-sm" onClick={() => setConnecting(true)}>Connect an agent</button>
              <a className="btn btn-stage btn-sm" href="/api/v1/platform-mcp" download>Download mcpgp-platform.zip</a>
            </div>
            <pre className="code code-wrap" style={{ border: 0, padding: 0 }}>{`# mcpgp-platform.yaml, next to the jar
instance:
  baseUrl: ${window.location.origin}
  auth: { type: bearer, token: "\${env:MCPGP_TOKEN}" }

# then, for example
claude mcp add mcpgp-platform -e MCPGP_TOKEN=<the agent's token> \\
  -- java -jar mcpgp-platform.jar --config mcpgp-platform.yaml`}</pre>
          </div>}
          <Approvals />
          <div>
            <h3 style={{ marginBottom: 8 }}>What agents and pipelines did</h3>
            <div className="list">
              {agentActivity.map((a, i) => (
                <div key={a.ts + i} className="stage-card">
                  <span><b style={{ color: 'var(--stage-bright)' }}>{a.actorKind}:{a.actor}</b> {describe(a)}</span>
                  <span className="meta">{ago(a.ts)} · {a.outcome}</span>
                </div>
              ))}
              {agents.isSuccess && agentActivity.length === 0 && <p className="empty">No agent activity yet.</p>}
            </div>
          </div>
        </section>
      </div>
    </>
  )
}

function IdentityRow({ identity, tokens, onIssued, canIssue, admin }: {
  identity: Identity; tokens: Token[]; onIssued: (t: Token) => void; canIssue: boolean; admin: boolean
}) {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const active = tokens.filter((t) => !t.revoked && (!t.expires || new Date(t.expires) > new Date()))
  const issue = useMutation({
    mutationFn: () => api.createToken({ identity: identity.id, label: `${identity.name} (web)` }),
    onSuccess: async (t) => { onIssued(t); await queryClient.invalidateQueries({ queryKey: ['tokens'] }) },
  })
  const revoke = useMutation({
    mutationFn: (id: string) => api.revokeToken(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['tokens'] }),
  })
  return (
    <div style={{ padding: '12px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div className="card-head">
        <span>
          <b>{identity.name}</b> <span className="tag">{identity.kind}</span>{identity.disabled && <span className="tag">disabled</span>}
          {identity.signsInWith && <span className="tag">{identity.signsInWith === 'oidc' ? 'company SSO' : 'directory'}</span>}<br />
          <span className="meta">{identity.scopes.includes('admin') ? 'admin' : identity.scopes.join(' · ')}{identity.rolesFromGroups ? ' · from their groups' : ''}</span>
        </span>
        <span className="actions">
          <button type="button" className="btn btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>{active.length} token{active.length === 1 ? '' : 's'}</button>
          {canIssue && <button type="button" className="btn btn-sm btn-soft" disabled={issue.isPending} onClick={() => issue.mutate()}>New token</button>}
        </span>
      </div>
      {admin && <PersonPassword identity={identity} />}
      <ErrorBox error={issue.error ?? revoke.error} />
      {open && (
        <div className="list">
          {active.map((t) => (
            <div key={t.id} className="row">
              <span><span className="mono">{t.id}</span> {t.label && <span className="hint">· {t.label}</span>}<br />
                <span className="meta">{t.scopes.join(' · ')} · expires {ago(t.expires)}</span></span>
              <button type="button" className="btn btn-sm btn-danger" disabled={revoke.isPending} onClick={() => revoke.mutate(t.id)}>Revoke</button>
            </div>
          ))}
          {active.length === 0 && <p className="hint">No active tokens.</p>}
        </div>
      )}
    </div>
  )
}

function NewIdentity({ onIssued }: { onIssued: (t: Token) => void }) {
  const queryClient = useQueryClient()
  const [kind, setKind] = useState<'agent' | 'pipeline' | 'person'>('agent')
  const [name, setName] = useState('')
  const [role, setRole] = useState('builder')
  const create = useMutation({
    mutationFn: async () => {
      const identity = await api.createIdentity({ kind, name, role })
      // agents and pipelines only sign in with a token: issue the first one right away
      if (kind !== 'person') onIssued(await api.createToken({ identity: identity.id, label: `${name} (first token)` }))
      return identity
    },
    onSuccess: async () => {
      setName('')
      await queryClient.invalidateQueries({ queryKey: ['identities'] })
      await queryClient.invalidateQueries({ queryKey: ['tokens'] })
    },
  })
  return (
    <form className="card" onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
      <h2>New identity</h2>
      <Segmented label="Kind" value={kind} onChange={setKind} options={[['agent', 'Agent'], ['pipeline', 'Pipeline'], ['person', 'Person']]} />
      <label className="field">Name
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label className="field">Role
        <select className="input" value={role} onChange={(e) => setRole(e.target.value)}>
          {ROLES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
        </select>
      </label>
      {kind === 'person' && <p className="hint">People sign in with company sign-in, or with a password: give them one with Give a password on their card.</p>}
      <ErrorBox error={create.error} />
      <div><button type="submit" className="btn btn-primary" disabled={!name || create.isPending}>Create {kind}</button></div>
    </form>
  )
}

function IssuedToken({ token, onClose }: { token: Token; onClose: () => void }) {
  return (
    <div className="card" role="status" style={{ borderColor: 'var(--accent)' }}>
      <div className="card-head"><h2>New token</h2><button type="button" className="btn btn-sm" onClick={onClose}>Done</button></div>
      <p className="hint">Shown once. Store it as a secret (for example in your pipeline's secret store); the platform keeps only its hash.</p>
      <div className="secret">{token.value}</div>
      <span className="meta">{token.scopes.join(' · ')} · expires {ago(token.expires)}</span>
    </div>
  )
}
