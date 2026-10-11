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

  it('offers the company account, and only that when passwords are off', async () => {
    fakeApi({ 'GET /platform': { auth: { local: false, ldap: false, oidc: true, oidcLabel: 'Acme SSO' } } })
    renderAt('/sign-in?error=no-access')
    const button = await screen.findByRole('link', { name: 'Sign in with Acme SSO' })
    expect(button.getAttribute('href')).toBe('/oauth2/authorization/oidc')
    expect(screen.queryByLabelText('Password')).toBeNull()
    expect(screen.getByRole('alert').textContent).toContain('none of your groups')
  })

  it('says the directory account works when LDAP is on', async () => {
    fakeApi({ 'GET /platform': { auth: { local: false, ldap: true, oidc: false } } })
    renderAt('/sign-in')
    await screen.findByText('Your directory account works here.')
    expect(screen.getByLabelText('Password')).toBeTruthy()
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

describe('choosing operations', () => {
  const accounts = { key: 'orders:GET:/accounts', method: 'GET', path: '/accounts', operationId: 'listAccounts', deprecated: false, tags: ['accounts'], contracts: ['2.0'] }
  const users = { key: 'orders:GET:/users', method: 'GET', path: '/users', operationId: 'listUsers', deprecated: false, tags: ['users-roles'], contracts: ['2.0'] }
  const select = { exclude: [{ tag: ['users-roles'] }] }
  const list = (accountsOn: boolean) => ({
    version: 3,
    apis: [{ id: 'orders', title: 'Orders', select: accountsOn ? select : { exclude: [...select.exclude, { operationId: 'listAccounts' }] }, operations: [
      accountsOn ? { ...accounts, state: 'tool', tool: 'orders_account_list' }
        : { ...accounts, state: 'excluded', exclusion: { kind: 'exclude', reason: 'excluded by select.exclude', index: 1, selector: { operationId: 'listAccounts' } } },
      { ...users, state: 'excluded', exclusion: { kind: 'exclude', reason: 'excluded by select.exclude', index: 0, selector: { tag: ['users-roles'] } } },
    ] }],
  })

  it('turns a tool off, previews it, and saves the next version', async () => {
    const { calls } = fakeApi({
      'GET /me': ME,
      'GET /settings': SETTINGS,
      'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
      'GET /servers/orders-mcp/versions/3/operations': list(true),
      'POST /servers/orders-mcp/edits': (c: { body: { dryRun?: boolean } }) => (c.body.dryRun
        ? { base: 3, digest: 'sha256:x', definition: 'apis: []\n', operations: list(false) }
        : { id: 'orders-mcp', latestVersion: 4, versions: [] }),
    })
    renderAt('/servers/orders-mcp/tools?view=operations')
    const toggle = await screen.findByRole('switch', { name: 'GET /accounts' })
    expect(toggle.getAttribute('aria-checked')).toBe('true')
    expect(screen.getByText('excluded by tag users-roles')).toBeTruthy()
    await userEvent.click(toggle)
    await waitFor(() => expect(screen.getByRole('switch', { name: 'GET /accounts' }).getAttribute('aria-checked')).toBe('false'))
    const change = { op: 'set', path: '/apis/[id=orders]/select/exclude', value: [{ tag: ['users-roles'] }, { operationId: 'listAccounts' }] }
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ base: 3, changes: [change], dryRun: true, operations: true })
    expect(screen.getByText('0 on, 1 off')).toBeTruthy()

    await userEvent.click(screen.getByRole('button', { name: 'Save as version 4' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2))
    expect(calls.filter((c) => c.method === 'POST')[1].body).toEqual({ base: 3, changes: [change], message: 'Tools & rules: off: orders_account_list' })
  })

  it('asks before turning on what a broader entry leaves out', async () => {
    fakeApi({
      'GET /me': ME,
      'GET /settings': SETTINGS,
      'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
      'GET /servers/orders-mcp/versions/3/operations': list(true),
    })
    renderAt('/servers/orders-mcp/tools?view=operations')
    await userEvent.click(await screen.findByRole('switch', { name: 'GET /users' }))
    expect(screen.getByRole('alertdialog').textContent).toContain('removes the exclude entry “tag users-roles”')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })
})

describe('naming', () => {
  const manifest = (name: string, computed: string, locked: boolean) => ({
    server: { name: 'orders-mcp', version: '2.0.0' }, exposure: { mode: 'plain' }, diagnostics: [], apis: [{ id: 'orders', toolPrefix: 'orders', contracts: [] }],
    tools: [{ name, kind: 'api', operation: { key: 'orders:GET:/customers/{id}' },
      provenance: { name: [{ step: 'namespace', value: computed }, ...(locked ? [{ step: 'lock', value: name, since: '1.0.0' }] : [])] } },
    { name: 'orders_account_list', kind: 'api', operation: { key: 'orders:GET:/accounts' }, provenance: { name: [{ step: 'namespace', value: 'orders_account_list' }] } }],
  })
  const routes = () => ({
    'GET /me': ME,
    'GET /settings': SETTINGS,
    'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
    'GET /servers/orders-mcp/versions/latest/manifest': manifest('orders_customer_get', 'orders_customer_account_get', true),
    'GET /servers/orders-mcp/versions/latest/naming': { extends: 'mcpgp://acme/naming/acme-standard@2', own: { vocabulary: { customer_account: 'customer' } },
      effective: { source: 'operationId', vocabulary: { 'retrieve|fetch': 'get', customer_account: 'customer' } }, lock: { present: true, entries: [] } },
  })

  it('shows the policy, the vocabulary and how each tool got its name', async () => {
    fakeApi(routes())
    renderAt('/servers/orders-mcp/tools?view=naming')
    expect(await screen.findByText('mcpgp://acme/naming/acme-standard@2')).toBeTruthy()
    expect(screen.getByRole('table', { name: 'Vocabulary' }).textContent).toContain('customer_accountcustomerthis server')
    await waitFor(() => expect(screen.getByRole('table', { name: 'Tool names' }).textContent).toContain('now: orders_customer_account_get'))
    expect(screen.getByRole('tab', { name: 'Would change (1)' })).toBeTruthy()
  })

  it('adds a vocabulary entry, shows which names change, and saves', async () => {
    const { calls } = fakeApi({
      ...routes(),
      'POST /servers/orders-mcp/edits': (c: { body: { dryRun?: boolean } }) => (c.body.dryRun
        ? { base: 3, digest: 'sha256:x', definition: '', manifest: { ...manifest('orders_customer_get', 'orders_customer_get', true),
          tools: [manifest('orders_customer_get', 'orders_customer_get', true).tools[0],
            { name: 'orders_acct_list', kind: 'api', operation: { key: 'orders:GET:/accounts' }, provenance: { name: [{ step: 'namespace', value: 'orders_acct_list' }] } }] } }
        : { id: 'orders-mcp', latestVersion: 4, versions: [] }),
    })
    renderAt('/servers/orders-mcp/tools?view=naming')
    await userEvent.type(await screen.findByLabelText('Words'), 'account')
    await userEvent.type(screen.getByLabelText('Become'), 'acct')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    const changes = await screen.findByRole('table', { name: 'Name changes' })
    await waitFor(() => expect(changes.textContent).toContain('orders_account_listorders_acct_list'))
    expect(screen.getByText(/breaks agents, prompts and clients/)).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Save as version 4' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && !(c.body as { dryRun?: boolean }).dryRun)).toHaveLength(1))
    expect(calls.filter((c) => c.method === 'POST').at(-1)!.body).toEqual({ base: 3, message: 'Naming: account → acct',
      changes: [{ op: 'set', path: '/naming/vocabulary/account', value: 'acct' }] })
  })
})

describe('package & deliver', () => {
  it('shows the formats, the docs as they ship and the contents before a build', async () => {
    fakeApi({
      'GET /me': ME,
      'GET /settings': SETTINGS,
      'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
      'GET /servers/orders-mcp/versions/latest/manifest': { server: { name: 'orders-mcp', version: '2.0.0' }, exposure: { mode: 'plain' }, diagnostics: [],
        tools: [], packs: [{ id: 'audit', version: '1', tools: [] }], runtime: { transports: { allowed: ['stdio'], default: 'stdio' } } },
      'GET /servers/orders-mcp/versions/3': { number: 3, root: 'orders.mcpgp.yaml', files: [] },
      'GET /servers/orders-mcp/builds': { items: [] },
      'GET /servers/orders-mcp/versions/3/package': { zip: 'orders-mcp-2.0.0.zip', signed: false, warnings: ['the package is not signed'],
        formats: [{ id: 'jar', file: 'orders-mcp.jar' }, { id: 'mcpb', file: 'orders-mcp-2.0.0.mcpb' }],
        files: ['orders-mcp.jar', 'orders-mcp-2.0.0.mcpb', 'docs/INSTALL.md', 'SHA256SUMS'],
        docs: { 'docs/README.md': '# Orders', 'docs/INSTALL.md': '# Installing Orders\n\nSee [the config](CONFIGURATION.md).', 'docs/CONFIGURATION.md': '# Configuration' } },
    })
    renderAt('/servers/orders-mcp/package')
    expect(await screen.findByRole('heading', { name: 'Installing Orders' })).toBeTruthy()
    const formats = screen.getByRole('region', { name: 'Package settings' }).textContent!
    expect(formats).toContain('Desktop bundle (.mcpb)')
    expect(formats).toContain('Not signed')
    await userEvent.click(screen.getByRole('button', { name: 'the config' }))
    expect(screen.getByRole('heading', { name: 'Configuration' })).toBeTruthy()
    await userEvent.click(screen.getByRole('tab', { name: 'Pipeline' }))
    expect(screen.getByText(/mcpgp build orders\.mcpgp\.yaml --frozen-lock/)).toBeTruthy()
  })
})

describe('shared naming policy', () => {
  it('edits a policy in the library, with who uses it', async () => {
    const item = { kind: 'naming', name: 'acme-standard', title: 'acme-standard', reference: 'mcpgp://acme/naming/acme-standard@2', updated: '',
      versions: [{ number: 1 }, { number: 2 }] }
    const { calls } = fakeApi({
      'GET /me': ME,
      'GET /settings': SETTINGS,
      'GET /library': { workspace: 'acme', items: [item] },
      'GET /library/naming/acme-standard': { ...item, root: 'acme-standard.yaml', policy: { vocabulary: { 'add|new': 'create' } },
        usedBy: [{ server: 'orders-mcp', pinned: '1' }, { server: 'billing-mcp', pinned: null }] },
      'GET /library/naming/acme-standard/versions/2/files/acme-standard.yaml': new Response('collisions: error\n'),
      'POST /library/naming/acme-standard/edits': (c: { body: { dryRun?: boolean } }) => (c.body.dryRun
        ? { base: 2, text: 'collisions: error\nmethodVerbs: { PUT: upsert }\n', problems: [], usedBy: [] } : { ...item, versions: [...item.versions, { number: 3 }] }),
    })
    renderAt('/library?naming=acme-standard')
    expect(await screen.findByText(/pins an older version/)).toBeTruthy()
    const put = screen.getByLabelText('PUT')
    await userEvent.clear(put)
    await userEvent.type(put, 'upsert{Enter}')
    await waitFor(() => expect(screen.getByLabelText('Changes to the policy').textContent).toContain('+ methodVerbs: { PUT: upsert }'))
    await userEvent.click(screen.getByRole('button', { name: 'Save as version 3' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST' && !(c.body as { dryRun?: boolean }).dryRun)).toHaveLength(1))
    expect(calls.filter((c) => c.method === 'POST').at(-1)!.body).toEqual({ base: 2, message: 'PUT → upsert',
      changes: [{ op: 'set', path: '/methodVerbs/PUT', value: 'upsert' }] })
  })
})

describe('descriptions', () => {
  const tool = { name: 'orders_order_refund', kind: 'api', title: 'Refund order', description: 'Refunds an order.',
    operation: { key: 'orders:POST:/orders/{id}/refund', operationId: 'refundOrder', tags: ['orders'] },
    inputSchema: { type: 'object', properties: { path: { type: 'object', properties: { id: { type: 'string' } } } } },
    provenance: { description: [{ step: 'spec', value: 'Refunds an order.' }] } }
  const manifest = { server: { name: 'orders-mcp', version: '2.0.0' }, exposure: { mode: 'plain' }, diagnostics: [], apis: [{ id: 'orders', contracts: [] }],
    tools: [tool], packs: [], instances: { mode: 'single' }, runtime: { transports: { allowed: ['stdio'], default: 'stdio' } }, policies: {}, build: {} }
  const routes = () => ({
    'GET /me': ME,
    'GET /settings': SETTINGS,
    'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
    'GET /servers/orders-mcp/versions/latest/manifest': manifest,
    'GET /servers/orders-mcp/versions/3/definition': { root: {}, resolved: {} },
    'GET /servers/orders-mcp/builds': { items: [] },
  })

  it('adds a notice for the tools that change data', async () => {
    const { calls } = fakeApi({ ...routes(), 'POST /servers/orders-mcp/edits': { base: 3, digest: 'x', definition: '', manifest } })
    renderAt('/servers/orders-mcp')
    await userEvent.click(await screen.findByRole('button', { name: 'Add notice' }))
    await userEvent.type(screen.getByLabelText('Text'), 'Changes need a ticket.')
    await userEvent.click(screen.getByRole('radio', { name: 'Tools that change data' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect((calls.find((c) => c.method === 'POST')!.body as { changes: unknown[] }).changes).toEqual([{ op: 'set', path: '/descriptions/notices/[id=changes-need-a-ticket]',
      value: { text: 'Changes need a ticket.', match: { method: ['POST', 'PUT', 'PATCH', 'DELETE'] } } }])
    expect(screen.getByText('after the description of tools that change data')).toBeTruthy()
  })

  it('changes one tool\'s title and parameter description as a rule', async () => {
    const { calls } = fakeApi({ ...routes(), 'POST /servers/orders-mcp/edits': { id: 'orders-mcp', latestVersion: 4, versions: [] } })
    renderAt('/servers/orders-mcp/tools?tool=orders_order_refund')
    await userEvent.click(await screen.findByRole('button', { name: 'Edit title and descriptions' }))
    const title = screen.getByLabelText('Title')
    await userEvent.clear(title)
    await userEvent.type(title, 'Refund an order')
    await userEvent.type(screen.getByLabelText('path.id'), 'The order.')
    await userEvent.click(screen.getByRole('button', { name: 'Save as version 4' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST')).toBe(true))
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ base: 3, message: 'Tools & rules: orders_order_refund title and descriptions', changes: [{
      op: 'set', path: '/overrides/rules/[id=ui:refundOrder]',
      value: { match: { operationId: 'refundOrder' }, title: { set: 'Refund an order' }, params: { 'path.id': { description: { set: 'The order.' } } } } }] })
  })
})

describe('trying a tool', () => {
  it('calls the tool against the mock and shows the result and the request', async () => {
    const tool = { name: 'orders_order_refund', kind: 'api', title: 'Refund order', description: 'Refunds.',
      operation: { key: 'orders:POST:/orders/{id}/refund', operationId: 'refundOrder' },
      inputSchema: { type: 'object', required: ['instance', 'path'], properties: { instance: { type: 'string' }, path: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } } } } }
    const { calls } = fakeApi({
      'GET /me': ME,
      'GET /settings': SETTINGS,
      'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 3, versions: [] },
      'GET /servers/orders-mcp/versions/latest/manifest': { server: { name: 'orders-mcp', version: '2.0.0' }, exposure: { mode: 'plain' }, diagnostics: [], apis: [], tools: [tool] },
      'POST /servers/orders-mcp/versions/3/try': new Response(JSON.stringify({ result: { structuredContent: { id: 'o-1', status: 'refunded' } },
        requests: [{ method: 'POST', path: '/api/v2/orders/example/refund', query: null, headers: { accept: 'application/json' }, body: '{}', status: 200 }], leftOut: [] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }),
    })
    renderAt('/servers/orders-mcp/tools?tool=orders_order_refund')
    await userEvent.click(await screen.findByRole('button', { name: 'Try it' }))
    expect((screen.getByLabelText('Arguments') as HTMLTextAreaElement).value).toContain('"instance": "mock"')
    await userEvent.click(screen.getByRole('button', { name: 'Call the tool' }))
    expect((await screen.findByLabelText('Result')).textContent).toContain('"status": "refunded"')
    expect(screen.getByLabelText('Request').textContent).toContain('POST /api/v2/orders/example/refund  → 200')
    expect(calls.find((c) => c.method === 'POST')!.body).toEqual({ tool: 'orders_order_refund', arguments: { instance: 'mock', path: { id: 'example' } } })
  })
})
