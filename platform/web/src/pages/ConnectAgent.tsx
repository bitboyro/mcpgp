import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Token } from '../api'
import { CLIENTS, agentName, clientSetup, platformConfig, type Client } from '../connect'
import { useSession } from '../session'
import { ErrorBox, Icon, Segmented, ago } from '../ui'

/**
 * Connect an agent to the platform's own MCP server: an agent identity and its first token, what to
 * paste into the agent's client, and a check that the agent called. Only admins create agents; others
 * are told to ask one, so agents keep their own quotas and their releases wait for a person.
 */
export function ConnectAgent({ onClose }: { onClose?: () => void }) {
  const { can } = useSession()
  return (
    <div className="list" style={{ gap: 16 }} aria-label="Connect an agent">
      <div className="card-head">
        <div>
          <div className="eyebrow">Platform MCP server</div>
          <h2>Connect an agent</h2>
        </div>
        {onClose && <button type="button" className="btn btn-stage btn-sm" aria-label="Close" onClick={onClose}><Icon name="close" size={14} /></button>}
      </div>
      <p className="hint">Your agent gets the platform as tools: it makes and changes servers, previews them, builds them. Its releases wait for a
        person's approval, and everything it does is in Activity.</p>
      {can('admin') ? <NewAgent /> : <AskAnAdmin />}
    </div>
  )
}

function AskAnAdmin() {
  return (
    <div className="stage-card">
      <span className="eyebrow">Ask an admin</span>
      <p>An agent has its own identity and token, so its quotas apply and its releases wait for a person. Only an admin can create one:
        ask an admin to create an agent for you on <b>API &amp; agents</b> and to give you its token. Then come back here, or follow
        the steps on <Link to="/agents">API &amp; agents</Link>.</p>
    </div>
  )
}

function NewAgent() {
  const { me } = useSession()
  const queryClient = useQueryClient()
  const [name, setName] = useState(agentName(me.name))
  const [role, setRole] = useState<'builder' | 'releaser'>('builder')
  const [token, setToken] = useState<Token | null>(null)
  const create = useMutation({
    mutationFn: async () => {
      const identity = await api.createIdentity({ kind: 'agent', name, role })
      return api.createToken({ identity: identity.id, label: `${name} (first token)` })
    },
    onSuccess: async (t) => {
      setToken(t)
      await queryClient.invalidateQueries({ queryKey: ['identities'] })
      await queryClient.invalidateQueries({ queryKey: ['tokens'] })
    },
  })
  if (token) return <Paste token={token} agent={name} />
  return (
    <form className="list" style={{ gap: 14 }} onSubmit={(e) => { e.preventDefault(); create.mutate() }}>
      <label className="field">Agent name
        <input className="input input-stage" value={name} onChange={(e) => setName(e.target.value)} required pattern="[A-Za-z0-9][A-Za-z0-9._@\-]{0,79}" />
      </label>
      <Segmented label="Role" value={role} onChange={setRole} options={[['builder', 'Builder'], ['releaser', 'Releaser']]} />
      <p className="hint">{role === 'builder' ? 'Makes and changes servers, and builds them.' : 'Also asks for releases; a person still approves each one.'}</p>
      <ErrorBox error={create.error} />
      <button type="submit" className="btn btn-primary" disabled={!name || create.isPending}>{create.isPending ? 'Creating…' : 'Create the agent'}</button>
    </form>
  )
}

function Paste({ token, agent }: { token: Token; agent: string }) {
  const [client, setClient] = useState<Client>('claude-code')
  const setup = clientSetup(client, token.value ?? '<the agent’s token>')
  const origin = window.location.origin
  // the agent's first call shows as the token's last use
  const tokens = useQuery({
    queryKey: ['tokens'],
    queryFn: api.tokens,
    refetchInterval: (q) => (q.state.data?.items.find((t) => t.id === token.id)?.lastUsed ? false : 3000),
  })
  const used = tokens.data?.items.find((t) => t.id === token.id)?.lastUsed
  return (
    <>
      <div className="stage-card">
        <span className="eyebrow">1 · The agent's token</span>
        <p className="hint">Shown once. It goes into the agent's client below; the platform keeps only its hash.</p>
        <div className="secret">{token.value}</div>
      </div>
      <div className="stage-card">
        <span className="eyebrow">2 · The server</span>
        <a className="btn btn-primary btn-sm" style={{ alignSelf: 'flex-start' }} href="/api/v1/platform-mcp" download><Icon name="download" />Download mcpgp-platform.zip</a>
        <p className="hint">Unzip it, and next to the jar save <span className="mono">mcpgp-platform.yaml</span>:</p>
        <pre className="code code-wrap" style={{ border: 0, padding: 0 }}>{platformConfig(origin)}</pre>
      </div>
      <div className="stage-card">
        <span className="eyebrow">3 · The agent's client</span>
        <Segmented label="Client" role="tab" value={client} onChange={setClient} options={CLIENTS} />
        <span className="meta">{setup.where}; replace the two paths with where you unzipped it</span>
        <pre className="code code-wrap" style={{ border: 0, padding: 0 }}>{setup.text}</pre>
      </div>
      <div className="stage-card" role="status">
        <span className="eyebrow">4 · Check</span>
        {used
          ? <p><b style={{ color: 'var(--stage-bright)' }}>✓ Connected.</b> {agent} called the platform {ago(used)}.</p>
          : <p>Waiting for {agent}'s first call… Ask it, for example, to list the servers on the platform.</p>}
      </div>
    </>
  )
}
