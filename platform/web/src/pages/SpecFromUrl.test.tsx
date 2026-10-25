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

const SERVER = { id: 'ledger', title: 'Ledger', archived: false, latestVersion: 2, created: '', updated: '', versions: [] }
const ADDRESS = 'https://specs.acme.com/ledger/openapi.yaml'
const DEFINITION = {
  root: { api: { id: 'ledger', contracts: [
    { version: '1.0', spec: 'contracts/openapi.yaml' },
    { version: '2.0', spec: 'contracts/2.0/openapi.yaml', default: true, source: ADDRESS },
  ] } },
}
const plan = (extra: object) => ({ api: 'ledger', version: '2.0', spec: 'contracts/2.0/openapi.yaml', replaces: true, compiles: true,
  diagnostics: [], moved: [], source: ADDRESS, ...extra })

function page() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/servers/ledger/apis']}><App /></MemoryRouter></QueryClientProvider>)
}

describe('specs from an address', () => {
  it('checks a version for updates, shows what agents would notice, and saves it', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/ledger': SERVER,
      'GET /servers/ledger/versions/latest/definition': DEFINITION,
      'POST /servers/ledger/contracts': (c: { path: string }) => c.path.includes('dryRun=true')
        ? plan({ unchanged: false, summary: '1 change since 2.0.0, none breaking', breaking: 0,
          changes: [{ impact: 'info', tool: 'ledger_entry_get', kind: 'tool.added', text: 'new tool' }] })
        : { ...SERVER, latestVersion: 3 },
    })
    page()
    expect(await screen.findByText(ADDRESS)).toBeTruthy()
    await userEvent.click(screen.getByRole('button', { name: 'Check for updates' }))
    expect(await screen.findByText('1 change since 2.0.0, none breaking')).toBeTruthy()
    expect(screen.getByText('ledger_entry_get')).toBeTruthy()
    const asked = calls.find((c) => c.method === 'POST')!
    expect(asked.path).toContain('url=' + encodeURIComponent(ADDRESS))
    expect(asked.path).toContain('version=2.0')
    expect(asked.body).toBeUndefined()

    await userEvent.click(screen.getByRole('button', { name: 'Save as the next revision' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'POST')).toHaveLength(2))
    expect(calls.filter((c) => c.method === 'POST')[1].path).not.toContain('dryRun')
  })

  it('says when nothing changed, and saves nothing', async () => {
    fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/ledger': SERVER,
      'GET /servers/ledger/versions/latest/definition': DEFINITION,
      'POST /servers/ledger/contracts': plan({ unchanged: true }),
    })
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Check for updates' }))
    expect(await screen.findByText('No changes: the API definition at this address matches the saved one.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Save as the next revision' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('adds a version from an address the person types', async () => {
    const { calls } = fakeApi({
      'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/ledger': SERVER,
      'GET /servers/ledger/versions/latest/definition': DEFINITION,
      'POST /servers/ledger/contracts': plan({ version: '3.0', replaces: false, spec: 'contracts/3.0/openapi.json', unchanged: false }),
    })
    page()
    await userEvent.click(await screen.findByRole('button', { name: 'Add an API version' }))
    await userEvent.click(screen.getByRole('radio', { name: 'From an address' }))
    await userEvent.type(screen.getByLabelText('Address of the API definition'), 'https://specs.acme.com/ledger/v3.json')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0) // nothing is fetched while typing
    await userEvent.click(screen.getByRole('button', { name: 'Fetch' }))
    expect(await screen.findByText(/Adds API version 3.0/)).toBeTruthy()
    expect(calls.find((c) => c.method === 'POST')!.path).toContain('url=' + encodeURIComponent('https://specs.acme.com/ledger/v3.json'))
  })
})
