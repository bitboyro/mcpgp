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

const SERVER = {
  id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, created: '', updated: '',
  versions: [1, 2, 3].map((n) => ({ number: n, digest: 'sha256:' + n, message: `v${n}`, author: 'ana', created: '2026-10-13T10:00:00Z' })),
}

const diff = (from: number, released: boolean) => ({
  from: { version: from, serverVersion: '2.0.0', released }, to: { version: 3, serverVersion: '2.1.0' }, released: 1,
  summary: '2 changes since 2.0.0, 1 breaking', breaking: 1, markdown: '### orders-mcp: …',
  changes: [
    { impact: 'breaking', tool: 'orders_customer_fetch', kind: 'tool.renamed', text: 'renamed from orders_customer_get' },
    { impact: 'info', tool: null, kind: 'contract.added', text: 'orders: contract 2.1 added' },
  ],
})

describe('changes between versions', () => {
  it('shows what changed since the last release, and another range on request', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/diff': diff(1, true),
      'GET /servers/orders-mcp/diff?from=2': diff(2, false),
    })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/servers/orders-mcp/changes']}><App /></MemoryRouter></QueryClientProvider>)
    expect(await screen.findByRole('heading', { name: '2 changes since 2.0.0, 1 breaking' })).toBeTruthy()
    expect(screen.getByText('renamed from orders_customer_get')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'orders_customer_fetch' }).getAttribute('href')).toBe('/servers/orders-mcp/tools?tool=orders_customer_fetch')
    expect(screen.getByText('Breaking · 1')).toBeTruthy()
    await userEvent.selectOptions(screen.getByLabelText('From'), '2')
    await waitFor(() => expect(calls.some((c) => c.path === '/servers/orders-mcp/diff?from=2')).toBe(true))
  })
})
