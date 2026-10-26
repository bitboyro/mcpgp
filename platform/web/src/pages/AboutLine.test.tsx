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

function page(build: object | undefined) {
  fakeApi({ 'GET /me': ME, 'GET /settings': { ...SETTINGS, build }, 'GET /servers': { items: [] }, 'GET /library': { items: [] } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/library']}><App /></MemoryRouter></QueryClientProvider>)
}

describe('version and build date', () => {
  it('shows the release and when it was built, with the details in About', async () => {
    page({ version: '0.4.0', time: '2026-10-28T09:15:00Z', commit: '1b1520f0c0ffee1234567890abcdef', java: '21.0.5 (Eclipse Adoptium)' })
    const line = await screen.findByRole('button', { name: /^mcpgp 0\.4\.0 · built / })
    expect(line.textContent).toContain('2026')
    expect(screen.queryByRole('region', { name: 'About this platform' })).toBeNull()
    await userEvent.click(line)
    const about = screen.getByRole('region', { name: 'About this platform' })
    expect(within(about).getByText('1b1520f0c0ff')).toBeTruthy()
    expect(within(about).getByText('21.0.5 (Eclipse Adoptium)')).toBeTruthy()
  })

  it('says what was not recorded instead of guessing', async () => {
    page({ version: '0.4.0', time: '2026-10-28T09:15:00Z', commit: null, java: '21' })
    await userEvent.click(await screen.findByRole('button', { name: /^mcpgp 0\.4\.0/ }))
    expect(within(screen.getByRole('region', { name: 'About this platform' })).getByText('not recorded')).toBeTruthy()
  })
})
