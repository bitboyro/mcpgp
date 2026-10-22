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
const manifest = (policies: object) => ({
  manifestVersion: '1.0', server: { name: 'orders-mcp', version: '2.0.0' }, instances: { mode: 'multi' }, apis: [], tools: [],
  runtime: {}, policies,
})

function page() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/servers/orders-mcp/safety']}><App /></MemoryRouter></QueryClientProvider>)
}

describe('security and safety', () => {
  it('saves batching and retention independently', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest({}),
      'POST /servers/orders-mcp/edits': SERVER,
    })
    page()
    await userEvent.click(await screen.findByLabelText('Enable batch calls'))
    await userEvent.click(screen.getByLabelText('Deduplicate retried batches'))
    await userEvent.clear(screen.getByLabelText('Request receipt lifetime (seconds)'))
    await userEvent.type(screen.getByLabelText('Request receipt lifetime (seconds)'), '3600')
    await userEvent.selectOptions(screen.getByLabelText('Result delivery'), 'auto')
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))
    await waitFor(() => expect(calls.some((c) => c.path.endsWith('/edits'))).toBe(true))
    const saved = calls.find((c) => c.path.endsWith('/edits'))!
    expect(saved.body).toMatchObject({ changes: [
      { op: 'set', path: '/batch/enabled', value: true },
      { op: 'set', path: '/batch/deduplicateRequests', value: true },
      { op: 'set', path: '/batch/requestReceiptTtlSeconds', value: 3600 },
      { op: 'set', path: '/results/mode', value: 'auto' },
    ] })
  })

  it('shows everything on by default, and saves only what changed', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest({ confirmDestructive: true }),   // a build from before these settings
      'POST /servers/orders-mcp/edits': SERVER,
    })
    page()
    const rate = await screen.findByLabelText('Limit calls to each target server') as HTMLInputElement
    expect(rate.checked).toBe(true)
    expect((screen.getByLabelText('Cut long lists at whole items') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('Limit the size of what agents send') as HTMLInputElement).checked).toBe(true)
    expect((screen.getByLabelText('Calls per minute') as HTMLInputElement).value).toBe('120')
    expect((screen.getByLabelText("Describe each tool's result (output schema)") as HTMLInputElement).checked).toBe(true)
    expect(screen.getByText('Always on')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save as the next version' }) as HTMLButtonElement).disabled).toBe(true)

    await userEvent.click(rate)
    expect((screen.getByLabelText('Calls per minute') as HTMLInputElement).disabled).toBe(true)
    await userEvent.clear(screen.getByLabelText('Largest answer an agent gets (KB)'))
    await userEvent.type(screen.getByLabelText('Largest answer an agent gets (KB)'), '512')
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))

    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const body = calls.find((c) => c.method === 'POST')!.body as { base: number; changes: unknown[] }
    expect(body.base).toBe(5)
    expect(body.changes).toEqual([
      { op: 'set', path: '/safety/responseLimitKb', value: 512 },
      { op: 'set', path: '/safety/rateLimits/enabled', value: false },
    ])
  })

  it('turns output schemas off in the definition', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest({ outputSchemas: true }),
      'POST /servers/orders-mcp/edits': SERVER,
    })
    page()
    await userEvent.click(await screen.findByLabelText("Describe each tool's result (output schema)"))
    await userEvent.click(screen.getByRole('button', { name: 'Save as the next version' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect((calls.find((c) => c.method === 'POST')!.body as { changes: unknown[] }).changes)
      .toEqual([{ op: 'set', path: '/schemas/output', value: false }])
  })

  it('refuses numbers that make no sense', async () => {
    fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest({ rateLimits: { enabled: true, concurrent: 8, perMinute: 120, retryAfter: true } }),
    })
    page()
    const perMinute = await screen.findByLabelText('Calls per minute')
    await userEvent.clear(perMinute)
    await userEvent.type(perMinute, '0')
    expect(screen.getByText('Numbers are whole and at least 1')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save as the next version' }) as HTMLButtonElement).disabled).toBe(true)
  })
})
