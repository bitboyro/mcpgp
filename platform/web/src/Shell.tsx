import { useQueryClient } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link, NavLink, useNavigate, useParams } from 'react-router-dom'
import { api } from './api'
import { useSession } from './session'
import { Icon } from './ui'

type IconName = Parameters<typeof Icon>[0]['name']

function Item({ to, icon, children, end }: { to: string; icon: IconName; children: ReactNode; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>
      <Icon name={icon} />{children}
    </NavLink>
  )
}

/** The dark sidebar: workspace pages, or a server's pages when one is open. */
export function Shell({ children }: { children: ReactNode }) {
  const { id } = useParams()
  const { me, settings } = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const signOut = async () => {
    try {
      await api.signOut()
    } finally {
      queryClient.clear()
      navigate('/sign-in', { replace: true })
    }
  }

  return (
    <div className="app">
      <nav className="nav" aria-label="Main">
        <Link to="/" className="brand"><span className="brand-mark"><Icon name="mark" stroke="#FFFFFF" width={2.2} /></span>mcpgp</Link>
        {id ? (
          <>
            <Link to={`/servers/${id}`} className="nav-card"><small>Server</small><span className="mono">{id}</span></Link>
            <div className="nav-links">
              <Item to="/" icon="home" end>All servers</Item>
              <Item to={`/servers/${id}`} icon="shape" end>Overview</Item>
              <Item to={`/servers/${id}/tools`} icon="tools">Tools &amp; rules</Item>
              <Item to={`/servers/${id}/connections`} icon="connections">Connections</Item>
              <Item to={`/servers/${id}/sources`} icon="sources">Sources</Item>
              <Item to={`/servers/${id}/package`} icon="deliver">Package &amp; deliver</Item>
              <Item to={`/servers/${id}/builds`} icon="package">Builds</Item>
              <Item to={`/servers/${id}/activity`} icon="activity">Activity</Item>
            </div>
          </>
        ) : (
          <>
            <div className="nav-card"><small>Workspace</small><span>{settings.workspace}</span></div>
            <div className="nav-links">
              <Item to="/" icon="home" end>All servers</Item>
              <Item to="/library" icon="library">Shared library</Item>
              <Item to="/builds" icon="builds">All builds</Item>
              <Item to="/activity" icon="activity">Activity</Item>
            </div>
          </>
        )}
        <div className="nav-foot">
          <Item to="/agents" icon="api">API &amp; agents</Item>
          <div className="nav-who"><span>{me.name}</span><button type="button" onClick={signOut}>Sign out</button></div>
        </div>
      </nav>
      <div className="main">{children}</div>
    </div>
  )
}
