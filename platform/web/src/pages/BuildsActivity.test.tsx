import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const now = new Date().toISOString()
const BUILD = { id: 'b-1', server: 'orders-mcp', version: 5, status: 'succeeded', startedBy: 'ana', created: now, started: now, finished: now,
  serverVersion: '2.0.0', released: true, archived: false, artifacts: [] }
const ACTIVITY = { items: [
  { ts: now, actor: 'ana', actorKind: 'person', action: 'build.release', target: 'b-1', outcome: 'ok', details: {} },
  { ts: now, actor: 'ci', actorKind: 'pipeline', action: 'build.start', target: 'b-2', outcome: 'ok', details: { server: 'orders-mcp', version: 6 } },
  { ts: now, actor: 'ana', actorKind: 'person', action: 'version.create', target: 'orders-mcp', outcome: 'ok', details: { version: 5 } },
  { ts: now, actor: 'ana', actorKind: 'person', action: 'build.start', target: 'b-1', outcome: 'ok', details: { server: 'orders-mcp', version: 5 } },
] }

function page(path: string) {
  fakeApi({
    'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': { id: 'orders-mcp', title: 'Orders', archived: false, latestVersion: 5, versions: [] },
    'GET /activity?server=orders-mcp&limit=200': ACTIVITY, 'GET /approvals': { items: [] },
    'GET /servers/orders-mcp/builds': { items: [BUILD] }, 'GET /builds/b-1': BUILD, 'GET /builds/b-1/log': 'done\n',
    'GET /builds/b-1/artifacts': { items: [] },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /></MemoryRouter></QueryClientProvider>)
}

describe('builds and activity', () => {
  it('filters Activity, and links build entries to their build', async () => {
    page('/servers/orders-mcp/activity')
    const timeline = await screen.findByRole('region', { name: 'Timeline' })
    expect(within(timeline).getAllByRole('radio').map((r) => r.textContent)).toEqual(['All', 'Changes', 'Builds', 'Agents'])
    expect(await within(timeline).findByText('saved revision 5 of orders-mcp')).toBeTruthy()
    await userEvent.click(within(timeline).getByRole('radio', { name: 'Builds' }))
    expect(within(timeline).queryByText('saved revision 5 of orders-mcp')).toBeNull()
    const links = within(timeline).getAllByRole('link', { name: 'Open the build' })
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/builds/b-1', '/builds/b-2', '/builds/b-1'])
  })

  it('shows only one build when Activity is opened for it', async () => {
    page('/servers/orders-mcp/activity?show=builds&build=b-1')
    const timeline = await screen.findByRole('region', { name: 'Timeline' })
    expect(await within(timeline).findByText('released build b-1')).toBeTruthy()
    expect(within(timeline).queryByText(/revision 6/)).toBeNull()
    expect(within(timeline).getAllByRole('listitem')).toHaveLength(2)
  })

  it("lists a build's own activity on its page, linking to the rest", async () => {
    page('/servers/orders-mcp/builds?build=b-1')
    const panel = await screen.findByLabelText('Build activity')
    expect(await within(panel).findByText(/released build b-1/)).toBeTruthy()
    expect(within(panel).queryByText(/revision 6/)).toBeNull()
    expect(within(panel).getByRole('link', { name: 'All of it in Activity' }).getAttribute('href')).toBe('/servers/orders-mcp/activity?show=builds&build=b-1')
    const intro = screen.getByText(/Packages built from this server/).closest('p')!
    expect(within(intro).getByRole('link', { name: 'Activity' }).getAttribute('href')).toBe('/servers/orders-mcp/activity?show=builds')
  })

  it('opens a build from its id alone, on its server', async () => {
    page('/builds/b-1')
    expect(await screen.findByRole('heading', { level: 1, name: 'Builds and releases' })).toBeTruthy()
    expect(await screen.findByLabelText('Build activity')).toBeTruthy()
  })
})
