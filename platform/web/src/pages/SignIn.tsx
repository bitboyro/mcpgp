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

  const auth = options.data?.auth
  const withPassword = auth ? auth.local || auth.ldap : true
  const returned = params.get('error')
  return (
    <main className="signin">
      <form onSubmit={submit} aria-labelledby="signin-title">
        <span className="brand-mark"><Icon name="mark" stroke="#FFFFFF" width={2.2} /></span>
        <div>
          <h1 id="signin-title">Sign in to mcpgp</h1>
          <p className="hint">The platform for building MCP servers from API definitions.</p>
        </div>
        {returned && <div className="error" role="alert">{RETURNED[returned] ?? RETURNED.sso}</div>}
        {auth?.oidc && (
          // a navigation, not a fetch: the provider's page takes over, then comes back to the platform
          <a className="btn btn-primary" href="/oauth2/authorization/oidc">Sign in with {auth.oidcLabel ?? 'your company account'}</a>
        )}
        {auth?.oidc && withPassword && <p className="hint" style={{ textAlign: 'center' }}>or</p>}
        {withPassword ? (
          <>
            <label className="field">Name
              <input className="input" name="name" autoComplete="username" required value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="field">Password
              <input className="input" name="password" type="password" autoComplete="current-password" required value={password}
                onChange={(e) => setPassword(e.target.value)} />
            </label>
            {auth?.ldap && <p className="hint">Your directory account works here{auth.local ? ', as do local accounts' : ''}.</p>}
            <ErrorBox error={error} />
            <button className={'btn ' + (auth?.oidc ? '' : 'btn-primary')} type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          </>
        ) : !auth?.oidc && options.isSuccess && (
          <p className="notice">No way of signing in is turned on for this installation. Ask its administrator.</p>
        )}
      </form>
    </main>
  )
}

const RETURNED: Record<string, string> = {
  'no-access': 'Your company account has no access to this platform: none of your groups is given a role here. Ask an administrator.',
  sso: 'Signing in with your company account didn’t work. Try again; if it keeps failing, ask an administrator.',
}
