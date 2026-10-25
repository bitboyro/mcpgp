import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('shows reusable starter templates and opens their source without applying them', async () => {
  const template = { kind: 'templates', name: 'basic', title: 'Basic', example: true, description: 'Local read-only defaults.', reference: 'mcpgp://acme/templates/basic@1', versions: [{ number: 1, author: 'mcpgp-examples', created: '' }], updated: '' }
  const { calls } = fakeApi({
    'GET /me': ME, 'GET /settings': SETTINGS,
    'GET /library': { workspace: 'acme', items: [template] },
    'GET /library/templates/basic': { ...template, root: 'basic.yaml' },
    'GET /library/templates/basic/versions/1/files/basic.yaml': 'configuration:\n  tools: {readOnly: true}',
  })
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><MemoryRouter initialEntries={['/library']}><App /></MemoryRouter></QueryClientProvider>)
  expect(await screen.findByText('Starter example')).toBeTruthy()
  await userEvent.click(screen.getByRole('button', { name: 'Open' }))
  expect(await screen.findByText(/configuration:.*tools:/s)).toBeTruthy()
  expect(calls.every(c => c.method === 'GET')).toBe(true)
})
