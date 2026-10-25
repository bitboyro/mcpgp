import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const SERVER = { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 5, created: '', updated: '', versions: [] }
const manifest = (runtime: object, runtimeDefaults: object = {}) => ({
  manifestVersion: '1.0', server: { name: 'orders-mcp', version: '2.0.0' }, instances: { mode: 'single' }, apis: [], tools: [],
  exposure: { mode: 'plain' }, diagnostics: [], runtime, policies: { runtimeDefaults },
})

function page(path: string, m: object) {
  const api = fakeApi({
    'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
    'GET /servers/orders-mcp/versions/latest/manifest': m,
    'GET /servers/orders-mcp/versions/latest/example-config': '# example\n',
    'POST /servers/orders-mcp/edits': SERVER,
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></QueryClientProvider>)
  return api
}

const posted = (calls: { method: string; body?: unknown }[]) => calls.find((c) => c.method === 'POST')!.body as { message: string; changes: unknown[] }

describe('access control', () => {
  it('saves the sign-in methods offered, the default and scope permissions as shipped defaults', async () => {
    const { calls } = page('/servers/orders-mcp/access', manifest({ transports: { allowed: ['stdio', 'http'], default: 'http' },
      inboundAuth: { allowed: ['none', 'static', 'oauth'], default: 'none' } }))
    await userEvent.click(await screen.findByLabelText('No sign-in'))
    expect(screen.getByText('The default method must be one of those offered')).toBeTruthy()
    await userEvent.selectOptions(screen.getByLabelText('Used unless the operator picks another'), 'oauth')
    await userEvent.click(screen.getByRole('button', { name: 'Add scope' }))
    await userEvent.type(screen.getByLabelText('Scope'), 'orders.read')
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(posted(calls)).toMatchObject({ message: 'Access control: sign-in methods, scope permissions', changes: [
      { op: 'set', path: '/runtimeDefaults/inboundAuth', value: { allowed: ['static', 'oauth'], default: 'oauth' } },
      { op: 'set', path: '/runtimeDefaults/configuration/server/auth/scopes', value: { 'orders.read': { access: 'read-only' } } },
    ] })
  })

  it('turns sessions on, and shows what the deployment and administrators set without editing it', async () => {
    const { calls } = page('/servers/orders-mcp/access', manifest({ transports: { allowed: ['stdio'], default: 'stdio' } }))
    await userEvent.click(await screen.findByLabelText('Let clients narrow their own access for a session'))
    expect(screen.getByText('--identity-policy <file>')).toBeTruthy()
    expect(screen.getByText('--organization-policy <file>')).toBeTruthy()
    expect(screen.getAllByText('Set when deployed').length).toBe(2)
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(posted(calls).changes).toEqual([{ op: 'set', path: '/runtimeDefaults/configuration/sessions/enabled', value: true }])
  })
})

describe('observability', () => {
  it('shows the runtime defaults where the package sets none, and saves only what changed', async () => {
    const { calls } = page('/servers/orders-mcp/observability', manifest({ transports: { allowed: ['stdio', 'http'], default: 'stdio' } }))
    const keep = await screen.findByLabelText('Keep for (days)') as HTMLInputElement
    expect(keep.value).toBe('30')
    expect((screen.getByLabelText('Write an audit file') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('Level') as HTMLSelectElement).value).toBe('info')
    await userEvent.clear(keep)
    await userEvent.type(keep, '90')
    await userEvent.selectOptions(screen.getByLabelText('Level'), 'warn')
    await userEvent.click(screen.getByLabelText('Send OpenTelemetry traces and metrics'))
    await userEvent.click(screen.getByLabelText('Traces'))
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(posted(calls).changes).toEqual([
      { op: 'set', path: '/runtimeDefaults/audit/file/retainDays', value: 90 },
      { op: 'set', path: '/runtimeDefaults/configuration/logging/level', value: 'warn' },
      { op: 'set', path: '/runtimeDefaults/telemetry/enabled', value: true },
      { op: 'set', path: '/runtimeDefaults/telemetry/signals', value: ['metrics'] },
    ])
  })

  it('refuses Prometheus for a server that only runs over stdio', async () => {
    page('/servers/orders-mcp/observability', manifest({ transports: { allowed: ['stdio'], default: 'stdio' } }, { telemetry: { enabled: true } }))
    await userEvent.selectOptions(await screen.findByLabelText('Exporter'), 'prometheus')
    expect(screen.getByText('Prometheus needs the HTTP transport; use OTLP for stdio servers')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save as the next version' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
