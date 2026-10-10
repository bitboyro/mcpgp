import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { App } from './App'
import { api, SIGNED_OUT, type Manifest, type Tool } from './api'
import { safeNext } from './pages/SignIn'
import { listedTools } from './serverData'
import { ME, SETTINGS, fakeApi } from './test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function Where() {
  const location = useLocation()
  return <output data-testid="where">{location.pathname + location.search}</output>
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const tool = (name: string, kind: Tool['kind']): Tool => ({ name, kind })

describe('what the agent sees', () => {
  const manifest = (mode: string, pinned: string[] = []) => ({
    exposure: { mode, pinned },
    tools: [tool('search_tools', 'builtin'), tool('orders_audit_list', 'pack'), tool('orders_order_get', 'api'), tool('orders_order_list', 'api')],
  }) as unknown as Manifest

  it('lists every tool in plain mode', () => {
    expect(listedTools(manifest('plain')).map((t) => t.name)).toHaveLength(4)
  })

  it('lists discovery and pack tools, plus pinned ones in hybrid mode', () => {
    expect(listedTools(manifest('discovery')).map((t) => t.name)).toEqual(['search_tools', 'orders_audit_list'])
    expect(listedTools(manifest('hybrid', ['orders_order_get'])).map((t) => t.name)).toEqual(['search_tools', 'orders_audit_list', 'orders_order_get'])
  })
})

describe('signing in', () => {
  it('only returns to pages on this site', () => {
    expect(safeNext('/servers/orders-mcp')).toBe('/servers/orders-mcp')
    expect(safeNext('//evil.example/x')).toBe('/')
    expect(safeNext('/\\evil.example')).toBe('/')
    expect(safeNext('https://evil.example')).toBe('/')
    expect(safeNext(null)).toBe('/')
  })

  it('sends people without a session to the sign-in page, and back after signing in', async () => {
    let signedIn = false
    const { calls } = fakeApi({
      'GET /me': () => signedIn ? ME : new Response(JSON.stringify({ code: 'UNAUTHORIZED', detail: 'a valid bearer token is required' }), { status: 401 }),
      'GET /platform': { auth: { local: true, oidc: false } },
      'POST /sessions': () => { signedIn = true; return { id: 't1', scopes: ['admin'] } },
      'GET /settings': SETTINGS,
      'GET /library': { workspace: 'acme', items: [] },
      'GET /activity': { items: [] },
      'GET /approvals': { items: [] },
    })
    renderAt('/activity')
    await screen.findByRole('heading', { name: 'Sign in to mcpgp' })
    expect(screen.getByTestId('where').textContent).toBe('/sign-in?next=%2Factivity')

    await userEvent.type(screen.getByLabelText('Name'), 'ana')
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await screen.findByRole('heading', { name: 'Activity' })
    const signIn = calls.find((c) => c.method === 'POST' && c.path === '/sessions')!
    // the session comes back as an HttpOnly cookie because the request says it is the web client
    expect(signIn.headers['X-Mcpgp-Client']).toBe('web')
    expect(signIn.body).toEqual({ name: 'ana', password: 'correct horse battery' })
    expect(screen.getByTestId('where').textContent).toBe('/activity')
  })

  it('says so when local accounts are off', async () => {
    fakeApi({ 'GET /platform': { auth: { local: false, oidc: false } } })
    renderAt('/sign-in')
    await screen.findByText(/Local accounts are turned off/)
    expect(screen.queryByLabelText('Password')).toBeNull()
  })

  it('tells the app when the session ends', async () => {
    fakeApi({ 'GET /servers': new Response(JSON.stringify({ code: 'UNAUTHORIZED', detail: 'expired' }), { status: 401 }) })
    const listener = vi.fn()
    window.addEventListener(SIGNED_OUT, listener)
    await expect(api.servers()).rejects.toThrow('expired')
    window.removeEventListener(SIGNED_OUT, listener)
    expect(listener).toHaveBeenCalledOnce()
  })
})

describe('all servers', () => {
  const routes = () => ({
    'GET /me': ME,
    'GET /settings': SETTINGS,
    'GET /servers': { items: [
      { id: 'orders-mcp', title: 'orders-mcp', archived: false, latestVersion: 2, updated: '2026-10-09T00:00:00Z' },
      { id: 'orders-readonly', title: 'orders-readonly', parent: 'orders-mcp', archived: false, latestVersion: 1, updated: '2026-10-09T00:00:00Z' },
    ] },
    'GET /servers/orders-mcp/versions/latest/manifest': { server: { name: 'orders-mcp', title: 'Example Orders API', version: '2.0.0' }, tools: [tool('a', 'api'), tool('b', 'api')] },
    'GET /servers/orders-readonly/versions/latest/manifest': { server: { name: 'orders-readonly', title: 'Orders (read only)', version: '2.0.0' }, tools: [tool('a', 'api')] },
    'GET /servers/orders-mcp/builds': { items: [{ id: 'b-1', server: 'orders-mcp', version: 2, status: 'succeeded', serverVersion: '2.0.0', released: true, archived: false, deleted: false, artifacts: [], created: '2026-10-09T00:00:00Z' }] },
    'GET /servers/orders-readonly/builds': { items: [] },
    'GET /library': { workspace: 'acme', items: [{ kind: 'naming', name: 'acme-standard', title: 'acme-standard', reference: 'mcpgp://acme/naming/acme-standard@2', versions: [], updated: '' }] },
  })

  it('shows each server with its tools, last build and variants', async () => {
    fakeApi(routes())
    renderAt('/')
    const card = await screen.findByRole('article', { name: 'orders-mcp' })
    await waitFor(() => expect(card.textContent).toContain('Example Orders API'))
    expect(card.textContent).toContain('2tools')
    expect(card.textContent).toContain('2.0.0last build')
    expect(card.textContent).toContain('1variants')
    expect(card.textContent).toContain('Released')
    expect((await screen.findByRole('article', { name: 'orders-readonly' })).textContent).toContain('variant of orders-mcp')
    expect(await screen.findByText('Naming policies')).toBeTruthy()
  })

  it('makes a variant that follows the original, and opens it', async () => {
    const { calls } = fakeApi({
      ...routes(),
      'POST /servers/orders-mcp/variants': (c: { body: { id: string } }) => ({ id: c.body.id, title: c.body.id, parent: 'orders-mcp', versions: [], latestVersion: 1 }),
      'GET /servers/orders-lite': { id: 'orders-lite', title: 'orders-lite', parent: 'orders-mcp', versions: [], latestVersion: null },
    })
    renderAt('/')
    const card = await screen.findByRole('article', { name: 'orders-mcp' })
    await userEvent.click(card.querySelector('button.btn-soft')!)
    expect(screen.getByText(/extends: mcpgp:\/\/acme\/servers\/orders-mcp@latest/)).toBeTruthy()
    const name = screen.getByLabelText('Name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Orders Lite')
    await userEvent.click(screen.getByRole('button', { name: 'Create variant' }))
    await waitFor(() => expect(screen.getByTestId('where').textContent).toBe('/servers/orders-lite'))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ id: 'orders-lite', pin: false })
  })
})
