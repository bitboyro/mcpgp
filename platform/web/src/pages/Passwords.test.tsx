import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { App } from '../App'
import type { Identity } from '../api'
import { ME, SETTINGS, fakeApi } from '../test-api'
import { PersonPassword } from './Passwords'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

function wrap(children: ReactNode, path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>)
}

describe('passwords', () => {
  it('a temporary password is replaced before anything else', async () => {
    const { calls } = fakeApi({
      'GET /me': { ...ME, password: true, mustChangePassword: true },
      'GET /settings': SETTINGS,
      'PUT /identities/id_1/password': { identity: 'id_1', mustChange: false },
    })
    wrap(<App />)
    expect(await screen.findByRole('heading', { name: 'Choose your own password' })).toBeTruthy()
    await userEvent.type(screen.getByLabelText('Temporary password'), 'temporary pass 1')
    await userEvent.type(screen.getByLabelText('New password'), 'my own password 2')
    await userEvent.type(screen.getByLabelText('New password again'), 'my own password X')
    expect(screen.getByText('The two don’t match.')).toBeTruthy()
    await userEvent.clear(screen.getByLabelText('New password again'))
    await userEvent.type(screen.getByLabelText('New password again'), 'my own password 2')
    await userEvent.click(screen.getByRole('button', { name: 'Save and continue' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ password: 'my own password 2', currentPassword: 'temporary pass 1' })
  })

  it('admins set a temporary password and unlock', async () => {
    const { calls } = fakeApi({
      'PUT /identities/id_2/password': { identity: 'id_2', mustChange: true },
      'POST /identities/id_2/unlock': { identity: 'id_2', locked: false },
    })
    const bob = { id: 'id_2', kind: 'person', name: 'bob', scopes: ['viewer'], created: '', disabled: false, password: true,
      lockedUntil: new Date(Date.now() + 600_000).toISOString() } as Identity
    wrap(<PersonPassword identity={bob} />)
    await userEvent.click(screen.getByRole('button', { name: 'Unlock' }))
    await waitFor(() => expect(calls.some((c) => c.path === '/identities/id_2/unlock')).toBe(true))
    await userEvent.click(screen.getByRole('button', { name: 'Set password' }))
    await userEvent.type(screen.getByLabelText('Password for bob'), 'temporary pass 1')
    await userEvent.type(screen.getByLabelText('Again'), 'temporary pass 1')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ password: 'temporary pass 1', mustChange: true })
  })
})
