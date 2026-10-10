import { useQuery, useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { api, ApiError, SIGNED_OUT, type Identity, type Settings } from './api'
import { Loading } from './ui'

interface Session {
  me: Identity
  settings: Settings
  /** What the session's token allows (admin allows everything). */
  can: (scope: string) => boolean
}

const SessionContext = createContext<Session | null>(null)

export function useSession(): Session {
  const session = useContext(SessionContext)
  if (!session) throw new Error('useSession outside a signed-in page')
  return session
}

/** Pages behind sign-in: loads who is signed in, or sends the browser to the sign-in page. */
export function RequireSession({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me, retry: false })
  const settings = useQuery({ queryKey: ['settings'], queryFn: api.settings, retry: false, enabled: me.isSuccess })

  useEffect(() => {
    const onSignedOut = () => {
      queryClient.clear()
      navigate('/sign-in?next=' + encodeURIComponent(location.pathname + location.search), { replace: true })
    }
    window.addEventListener(SIGNED_OUT, onSignedOut)
    return () => window.removeEventListener(SIGNED_OUT, onSignedOut)
  }, [navigate, queryClient, location])

  if (me.error instanceof ApiError && me.error.status === 401) {
    return <Navigate to={'/sign-in?next=' + encodeURIComponent(location.pathname + location.search)} replace />
  }
  if (me.error || settings.error) return <div className="error" role="alert">The platform can't be reached: {(me.error ?? settings.error)?.message}</div>
  if (!me.data || !settings.data) return <Loading />
  const scopes = new Set(me.data.tokenScopes ?? me.data.scopes)
  const can = (scope: string) => scopes.has('admin') || scopes.has(scope)
  return <SessionContext.Provider value={{ me: me.data, settings: settings.data, can }}>{children}</SessionContext.Provider>
}
