import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const SERVER = { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 5, created: '', updated: '',
  versions: [{ number: 5, author: 'ana', created: new Date().toISOString(), digest: 'sha256:x' }] }
const MANIFEST = {
  manifestVersion: '1.0', server: { name: 'orders-mcp', title: 'Orders MCP', version: '2.0.0' }, build: {},
  apis: [{ id: 'orders', title: 'Orders API', toolPrefix: 'orders', contracts: [{ version: '2.0', format: 'openapi-3.1', default: true }],
    upstreamAuth: { supported: ['basic', 'apiKey'] } }],
  tools: [{ name: 'orders_customer_get', kind: 'api', api: 'orders', annotations: { readOnlyHint: true }, variants: { '2.0': { method: 'GET' } } }],
  exposure: { mode: 'plain' }, instances: { mode: 'single' }, packs: [], diagnostics: [],
  runtime: { transports: { allowed: ['stdio', 'http'], default: 'stdio' }, inboundAuth: { allowed: ['none', 'static', 'oauth'], default: 'none' } },
  policies: { confirmDestructive: true, runtimeDefaults: {} },
}

function page(path: string) {
  const api = fakeApi({
    'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
    'GET /servers/orders-mcp/versions/latest/manifest': MANIFEST,
    'GET /servers/orders-mcp/builds': { items: [{ id: 'b-1', server: 'orders-mcp', version: 5, status: 'failed', startedBy: 'ana',
      created: new Date().toISOString(), started: null, finished: null, serverVersion: null, released: false, archived: false }] },
    'GET /servers/orders-mcp/versions/latest/example-config': '# example\n',
    'GET /servers/orders-mcp/versions/5/package': { zip: 'orders-mcp-2.0.0.zip', signed: false, warnings: [], formats: [{ id: 'jar', file: 'orders-mcp.jar' }],
      files: ['orders-mcp.jar'], docs: { 'docs/README.md': '# Orders' } },
    'POST /servers/orders-mcp/edits': SERVER,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></QueryClientProvider>)
  return api
}

describe('overview', () => {
  it('shows one card per area with its state, and the most important next step', async () => {
    page('/servers/orders-mcp')
    const areas = await screen.findByRole('region', { name: 'Areas' })
    const cards = within(areas).getAllByRole('link')
    expect(cards.map((c) => c.getAttribute('aria-label'))).toEqual([
      'API definitions: Valid', 'Tools: 1 of 1 operations', 'Target APIs: One target server', 'Access control: No sign-in by default',
      'Execution: Confirms changes', 'Observability: Package defaults', 'Delivery: Last build failed', 'General: No instructions'])
    expect(within(areas).getByRole('link', { name: 'Access control: No sign-in by default' }).getAttribute('href')).toBe('/servers/orders-mcp/access')
    expect(screen.getByRole('link', { name: /^Next: choose how clients sign in/ }).getAttribute('href')).toBe('/servers/orders-mcp/access')
    expect(screen.queryByText("What the agent sees")).toBeNull()   // settings live on their pages now
  })

  it('shows the agent preview on request', async () => {
    page('/servers/orders-mcp')
    await userEvent.click(await screen.findByRole('button', { name: 'Preview as agent' }))
    expect(screen.getByRole('region', { name: 'Preview' })).toBeTruthy()
  })
})

describe('settings moved from Overview', () => {
  it('saves what the agent sees and the support packs on Tools as the next revision', async () => {
    const { calls } = page('/servers/orders-mcp/tools')
    await userEvent.click(await screen.findByRole('radio', { name: 'Search first' }))
    await userEvent.click(screen.getByRole('button', { name: 'Explain tools' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save as revision 6' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toMatchObject({ base: 5,
      message: 'Tools: What the agent sees: Search first; Explain tools: on',
      changes: [{ op: 'set', path: '/exposure/mode', value: 'discovery' }, { op: 'set', path: '/packs/introspection', value: {} }] })
  })

  it('sets how many target servers on Target APIs, and undoing leaves nothing to save', async () => {
    const { calls } = page('/servers/orders-mcp/targets')
    await userEvent.click(await screen.findByRole('radio', { name: 'Several, per call' }))
    expect(screen.getByText('1 unsaved change')).toBeTruthy()
    await userEvent.click(screen.getByRole('radio', { name: 'One server' }))
    expect(screen.queryByRole('button', { name: /^Save as revision/ })).toBeNull()
    await userEvent.click(screen.getByRole('radio', { name: 'Several, per call' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save as revision 6' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect((calls.find((c) => c.method === 'POST')!.body as { changes: unknown[] }).changes).toEqual([{ op: 'set', path: '/instances/mode', value: 'multi' }])
  })

  it('sets the transports on Packaging', async () => {
    page('/servers/orders-mcp/packaging')
    expect(await screen.findByRole('radiogroup', { name: 'How agents connect' })).toBeTruthy()
  })
})
