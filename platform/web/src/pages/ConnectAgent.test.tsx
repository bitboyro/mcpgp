import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { agentName, clientSetup, platformConfig } from '../connect'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></QueryClientProvider>)
}

const PLATFORM = { id: 'mcpgp-platform', title: 'mcpgp platform', archived: false, latestVersion: 1, updated: '2026-10-13T00:00:00Z', system: true }

const routes = (me = ME, servers: unknown[] = [PLATFORM]) => ({
  'GET /me': me,
  'GET /settings': SETTINGS,
  'GET /servers': { items: servers },
  'GET /servers/mcpgp-platform/versions/latest/manifest': { server: { name: 'mcpgp-platform', title: 'mcpgp platform', version: '0.2.8' }, tools: [] },
  'GET /servers/mcpgp-platform/builds': { items: [] },
  'GET /library': { workspace: 'acme', items: [] },
})

describe('connect an agent', () => {
  it('writes what each client needs', () => {
    expect(platformConfig('https://mcpgp.acme.internal')).toContain('baseUrl: https://mcpgp.acme.internal')
    expect(clientSetup('claude-code', 'mcpgp_t_s').text).toBe('claude mcp add mcpgp-platform -e MCPGP_TOKEN=mcpgp_t_s -- java -jar '
      + '/path/to/mcpgp-platform.jar --config /path/to/mcpgp-platform.yaml')
    expect(JSON.parse(clientSetup('vscode', 'mcpgp_t_s').text).servers['mcpgp-platform']).toMatchObject({ type: 'stdio', env: { MCPGP_TOKEN: 'mcpgp_t_s' } })
    expect(JSON.parse(clientSetup('cursor', 'mcpgp_t_s').text).mcpServers['mcpgp-platform'].command).toBe('java')
    expect(agentName('ana@acme.com')).toBe('ana-assistant')
  })

  it('starts there on a platform with only its own server, and connects', async () => {
    let used: string | null = null
    const { calls } = fakeApi({
      ...routes(),
      'POST /identities': { id: 'id_9', kind: 'agent', name: 'ana-assistant', scopes: [], created: '', disabled: false },
      'POST /tokens': { id: 'tok_9', identity: 'id_9', scopes: ['servers:read'], created: '', expires: null, revoked: false, value: 'mcpgp_tok9_secret' },
      'GET /tokens': () => ({ items: [{ id: 'tok_9', identity: 'id_9', scopes: [], created: '', expires: null, revoked: false, lastUsed: used }] }),
    })
    renderAt('/')
    const card = await screen.findByRole('article', { name: 'mcpgp-platform' })
    expect(within(card).getByText('Made by the platform')).toBeTruthy()
    expect(within(card).queryByRole('button', { name: 'Duplicate' })).toBeNull()
    expect(await screen.findByText('No servers yet')).toBeTruthy()

    const panel = await screen.findByLabelText('Connect an agent')
    expect((within(panel).getByLabelText('Agent name') as HTMLInputElement).value).toBe('ana-assistant')
    await userEvent.click(within(panel).getByRole('button', { name: 'Create the agent' }))
    expect(await within(panel).findByText('mcpgp_tok9_secret')).toBeTruthy()
    expect(calls.find((c) => c.method === 'POST' && c.path === '/identities')!.body).toEqual({ kind: 'agent', name: 'ana-assistant', role: 'builder' })
    expect(panel.textContent).toContain('claude mcp add mcpgp-platform -e MCPGP_TOKEN=mcpgp_tok9_secret')
    await userEvent.click(within(panel).getByRole('tab', { name: 'VS Code' }))
    expect(panel.textContent).toContain('.vscode/mcp.json')
    expect(within(panel).getByRole('status').textContent).toContain('Waiting for ana-assistant')

    used = new Date().toISOString()
    await waitFor(() => expect(within(panel).getByRole('status').textContent).toContain('Connected'), { timeout: 5000 })
  })

  it('tells people who aren’t admins to ask one', async () => {
    fakeApi(routes({ ...ME, scopes: ['servers:read', 'servers:write'], tokenScopes: ['servers:read', 'servers:write'] }))
    renderAt('/?connect')
    const panel = await screen.findByLabelText('Connect an agent')
    expect(within(panel).getByText('Ask an admin')).toBeTruthy()
    expect(within(panel).queryByRole('button', { name: 'Create the agent' })).toBeNull()
  })

  it('opens the shared library, not Connect, once there are servers of your own', async () => {
    fakeApi({ ...routes(ME, [PLATFORM, { id: 'orders-mcp', title: 'orders-mcp', archived: false, latestVersion: 1, updated: '' }]),
      'GET /servers/orders-mcp/versions/latest/manifest': { server: { name: 'orders-mcp', title: 'Orders', version: '1' }, tools: [] },
      'GET /servers/orders-mcp/builds': { items: [] } })
    renderAt('/')
    expect(await screen.findByText('Shared in this workspace')).toBeTruthy()
    expect(screen.queryByLabelText('Connect an agent')).toBeNull()
    const cards = await screen.findAllByRole('article')
    expect(cards[0].getAttribute('aria-label')).toBe('mcpgp-platform')
  })

  it('shows the platform’s server read-only', async () => {
    fakeApi({
      ...routes(),
      'GET /servers/mcpgp-platform': { ...PLATFORM, created: '', versions: [{ number: 1, digest: 'sha256:aa', author: 'system', created: '' }] },
      'GET /servers/mcpgp-platform/versions/1/definition': { root: {}, resolved: {} },
    })
    renderAt('/servers/mcpgp-platform')
    expect(await screen.findByRole('heading', { name: 'Made by the platform' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Upload version' })).toBeNull()
    expect(screen.getAllByRole('link', { name: 'Connect an agent' })[0].getAttribute('href')).toBe('/?connect')
  })
})
