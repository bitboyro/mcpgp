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

const SERVER = { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 4, created: '', updated: '', versions: [] }

const manifest = (contracts: string[], detect?: object) => ({
  manifestVersion: '1.0', server: { name: 'orders-mcp', version: '2.0.0' }, instances: { mode: 'multi' },
  apis: [{
    id: 'orders', title: 'Orders', toolPrefix: 'orders', detect,
    contracts: contracts.map((v, i) => ({ version: v, format: 'openapi-3.1', basePath: `/api/v${v[0]}`, default: i === contracts.length - 1 })),
  }],
  tools: [],
})

function page() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/servers/orders-mcp/apis']}><App /></MemoryRouter></QueryClientProvider>)
}

describe('version detection on API definitions', () => {
  it('is turned on with a rule that reads the version from the JSON answer under each base path', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest(['1.0', '2.0']),
      'POST /servers/orders-mcp/edits': SERVER,
    })
    page()
    expect(await screen.findByText('No: the default version is used')).toBeTruthy()
    await userEvent.clear(screen.getByLabelText('Pattern (its first group is the version)'))
    await userEvent.type(screen.getByLabelText('Pattern (its first group is the version)'), '[[')
    expect(screen.getByText('The pattern isn’t a valid regular expression')).toBeTruthy()
    await userEvent.clear(screen.getByLabelText('Pattern (its first group is the version)'))
    await userEvent.type(screen.getByLabelText('Pattern (its first group is the version)'), '^v?(\\d+)')
    await userEvent.selectOptions(screen.getByLabelText('If the version can’t be found'), 'refuse')
    await userEvent.click(screen.getByRole('button', { name: 'Turn on' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    const saved = calls.find((c) => c.method === 'POST')!.body as { base: number; changes: { op: string; path: string; value: unknown }[] }
    expect(saved.base).toBe(4)
    expect(saved.changes).toEqual([{ op: 'set', path: '/apis/[id=orders]/detect', value: {
      method: 'GET', path: '/version', pattern: '^v?(\\d+)', underBasePath: true, field: '/version', onFailure: 'refuse',
    } }])
  })

  it('reads a header instead, and is turned off', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest(['1.0', '2.0'], { method: 'GET', path: '/version', pattern: '^(\\d+)', header: 'X-Api-Version' }),
      'POST /servers/orders-mcp/edits': SERVER,
    })
    page()
    expect(await screen.findByText('GET /version')).toBeTruthy()
    expect((screen.getByLabelText('Header') as HTMLInputElement).value).toBe('X-Api-Version')
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false)
    await userEvent.click(screen.getByRole('button', { name: 'Turn off' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect((calls.find((c) => c.method === 'POST')!.body as { changes: unknown[] }).changes)
      .toEqual([{ op: 'remove', path: '/apis/[id=orders]/detect' }])
  })

  it('is not offered with a single version', async () => {
    fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
      'GET /servers/orders-mcp/versions/latest/manifest': manifest(['2.0']),
    })
    page()
    expect(await screen.findByText('Orders: contracts shipped')).toBeTruthy()
    expect(screen.queryByText('Which version a customer’s server runs')).toBeNull()
  })
})
