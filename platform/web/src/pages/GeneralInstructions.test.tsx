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
const manifest = (instructions?: string) => ({
  manifestVersion: '1.0', server: { name: 'orders-mcp', version: '2.0.0', instructions }, build: {}, apis: [], tools: [],
  exposure: { mode: 'plain' }, instances: { mode: 'multi' }, packs: [], diagnostics: [], runtime: {}, policies: {},
})

function page(saved?: string) {
  const api = fakeApi({
    'GET /me': ME, 'GET /settings': SETTINGS, 'GET /servers/orders-mcp': SERVER,
    'GET /servers/orders-mcp/versions/latest/manifest': manifest(saved ?? 'Orders for the team. Every tool requires `instance`.'),
    'GET /servers/orders-mcp/versions/5/definition': { version: 5, root: { metadata: { name: 'orders-mcp', description: 'Orders for the team.',
      ...(saved ? { instructions: saved } : {}) } }, resolved: {} },
    // the compiler's answer: a dry run previews, otherwise the edit is saved
    'POST /servers/orders-mcp/edits': (call: { body?: unknown }) => {
      const body = call.body as { dryRun?: boolean; changes: { value?: string }[] }
      return body.dryRun ? { base: 5, digest: 'x', definition: '', manifest: manifest(`${body.changes[0].value ?? ''}\n\nEvery tool requires \`instance\`.`) } : SERVER
    },
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/servers/orders-mcp/general']}><App /></MemoryRouter></QueryClientProvider>)
  return api
}

describe('instructions for agents', () => {
  it('previews what agents receive while typing, and saves the instructions as written', async () => {
    const { calls } = page()
    const box = await screen.findByRole('textbox', { name: 'Instructions for agents' })
    expect(screen.getByText('Empty: agents get the description, “Orders for the team.”.')).toBeTruthy()
    await userEvent.type(box, 'Look up orders.{enter}{enter}- Search first.')
    const received = screen.getByLabelText('What agents receive')
    await waitFor(() => expect(within(received).getByText(/Search first\.\s+Every tool requires `instance`\./)).toBeTruthy())
    await userEvent.click(screen.getByRole('button', { name: 'Save as revision 6' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && !(c.body as { dryRun?: boolean }).dryRun)).toBe(true))
    expect(calls.filter((c) => c.method === 'POST').at(-1)!.body).toMatchObject({ base: 5, message: 'General: instructions for agents',
      changes: [{ op: 'set', path: '/metadata/instructions', value: 'Look up orders.\n\n- Search first.' }] })
  })

  it('removes the instructions when the text is cleared', async () => {
    const { calls } = page('Look up orders.')
    const box = await screen.findByRole('textbox', { name: 'Instructions for agents' })
    await waitFor(() => expect((box as HTMLTextAreaElement).value).toBe('Look up orders.'))
    await userEvent.clear(box)
    await userEvent.click(screen.getByRole('button', { name: 'Save as revision 6' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'POST' && !(c.body as { dryRun?: boolean }).dryRun)).toBe(true))
    expect((calls.filter((c) => c.method === 'POST').at(-1)!.body as { changes: unknown[] }).changes).toEqual([{ op: 'remove', path: '/metadata/instructions' }])
  })
})
