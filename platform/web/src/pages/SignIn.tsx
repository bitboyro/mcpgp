import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, Icon } from '../ui'

/** Only paths on this site: never send the browser elsewhere after signing in. */
export function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') ? next : '/'
}

export function SignInPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const options = useQuery({ queryKey: ['sign-in-options'], queryFn: api.signInOptions })
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.signIn(name, password)
      queryClient.clear()
      navigate(safeNext(params.get('next')), { replace: true })
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const local = options.data?.auth.local ?? true
  return (
    <main className="signin">
      <form onSubmit={submit} aria-labelledby="signin-title">
        <span className="brand-mark"><Icon name="mark" stroke="#FFFFFF" width={2.2} /></span>
        <div>
          <h1 id="signin-title">Sign in to mcpgp</h1>
          <p className="hint">The platform for building MCP servers from API definitions.</p>
        </div>
        {local ? (
          <>
            <label className="field">Name
              <input className="input" name="name" autoComplete="username" required value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="field">Password
              <input className="input" name="password" type="password" autoComplete="current-password" required value={password}
                onChange={(e) => setPassword(e.target.value)} />
            </label>
            <ErrorBox error={error} />
            <button className="btn btn-primary" type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          </>
        ) : (
          <p className="notice">Local accounts are turned off on this installation. Sign in with your company account.</p>
        )}
      </form>
    </main>
  )
}
