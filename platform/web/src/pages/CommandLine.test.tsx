import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import { ME, SETTINGS, fakeApi } from '../test-api'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function page(extra: object) {
  fakeApi({ 'GET /me': ME, 'GET /settings': { ...SETTINGS, ...extra } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/cli']}><App /></MemoryRouter></QueryClientProvider>)
}

describe('command line', () => {
  it('shows where to get the CLI that matches this platform, how to use it, and how to push here', async () => {
    page({ build: { version: '0.4.0', time: null, commit: null, java: '21' }, cli: { image: 'docker.io/acme/mcpgp-cli:0.4.0' },
      publicUrl: 'https://mcpgp.acme.internal' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Command line' })).toBeTruthy()
    expect(screen.getByText('Use mcpgp 0.4.0, the version of this platform, so both compile the same way.')).toBeTruthy()
    expect(screen.getByText('docker.io/acme/mcpgp-cli:0.4.0')).toBeTruthy()
    expect(screen.getByRole('link', { name: /mcpgp-cli-0\.4\.0\.zip on the release page/ }).getAttribute('href'))
      .toBe('https://github.com/bitboyro/mcpgp/releases/tag/v0.4.0')
    expect(screen.getByText(/export MCPGP_PLATFORM=https:\/\/mcpgp\.acme\.internal/)).toBeTruthy()
    expect(screen.getByText(/mcpgp push orders-mcp\.mcpgp\.yaml --build/)).toBeTruthy()
    expect(screen.getByRole('link', { name: 'API and agents' }).getAttribute('href')).toBe('/agents')
    expect(screen.getByRole('link', { name: 'the CLI reference' }).getAttribute('href')).toBe('https://github.com/bitboyro/mcpgp/blob/v0.4.0/docs/spec/cli.md')
    const developers = screen.getByLabelText('For developers')
    expect(within(developers).getByRole('link', { name: 'Command line' }).getAttribute('href')).toBe('/cli')
  })

  it('says when it cannot name the image, for a platform built locally', async () => {
    page({ build: { version: '0.4.0', time: null, commit: null, java: '21' }, cli: { image: null } })
    expect(await screen.findByText('docker.io/<account>/mcpgp-cli:0.4.0')).toBeTruthy()
    expect(screen.getByText(/built locally, so it doesn't know your registry/)).toBeTruthy()
  })
})
