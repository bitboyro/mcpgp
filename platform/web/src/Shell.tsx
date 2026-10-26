import { useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, NavLink, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, type Settings } from './api'
import { useSession } from './session'
import { ChangePassword } from './pages/Passwords'
import { Icon } from './ui'

type IconName = Parameters<typeof Icon>[0]['name']

function Item({ to, icon, children, end }: { to: string; icon: IconName; children: ReactNode; end?: boolean }) {
  return (
    <NavLink to={to} end={end} className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>
      <Icon name={icon} />{children}
    </NavLink>
  )
}

/** The platform's version and build date, always visible; the details open in an About panel (R08). */
function AboutLine({ build }: { build?: Settings['build'] }) {
  const [open, setOpen] = useState(false)
  const date = build?.time ? new Date(build.time).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : null
  return (
    <div className="nav-about">
      <button type="button" className="nav-version" aria-expanded={open} aria-controls="about-panel" onClick={() => setOpen(!open)}>
        mcpgp {build?.version ?? 'development'}{date && ` · built ${date}`}<Icon name="info" size={13} />
      </button>
      {open && (
        <div id="about-panel" className="about" role="region" aria-label="About this platform">
          <dl className="kv">
            <dt>Version</dt><dd>{build?.version ?? 'not recorded'}</dd>
            <dt>Built</dt><dd>{build?.time ? new Date(build.time).toLocaleString() : 'not recorded'}</dd>
            <dt>Commit</dt><dd className="mono" title={build?.commit ?? undefined}>{build?.commit ? build.commit.slice(0, 12) : 'not recorded'}</dd>
            <dt>Java</dt><dd>{build?.java ?? 'unknown'}</dd>
          </dl>
        </div>
      )}
    </div>
  )
}

/** A labelled group of server pages (Configure, Deliver, History): the configuration groups of design/ui.md. */
function Group({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={label} className="nav-group">
      <span className="nav-group-label" aria-hidden="true">{label}</span>
      {children}
    </div>
  )
}

/** The dark sidebar: workspace pages, or a server's pages when one is open. */
export function Shell({ children }: { children: ReactNode }) {
  const { id } = useParams()
  const { me, settings } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()

  const signOut = async () => {
    try {
      await api.signOut()
    } finally {
      queryClient.clear()
      navigate('/sign-in', { replace: true })
    }
  }

  // a temporary password an admin set: nothing else works until it's replaced (AuthFilter answers PASSWORD_CHANGE_REQUIRED)
  if (me.mustChangePassword) {
    return (
      <div className="body" style={{ justifyContent: 'center', padding: 24 }}>
        <section className="col" aria-label="Replace the temporary password">
          <ChangePassword me={me} forced />
          <button type="button" className="btn btn-sm" style={{ alignSelf: 'flex-start' }} onClick={signOut}>Sign out</button>
        </section>
      </div>
    )
  }

  return (
    <div className="app">
      <nav className="nav" aria-label="Main">
        <Link to="/" className="brand"><span className="brand-mark"><Icon name="mark" stroke="#FFFFFF" width={2.2} /></span>mcpgp</Link>
        {id ? (
          <>
            <Link to={`/servers/${id}`} className="nav-card"><small>MCP server</small><span className="mono">{id}</span></Link>
            <div className="nav-links">
              <Item to="/" icon="home" end>All MCP Servers</Item>
              <Item to={`/servers/${id}`} icon="overview" end>Overview</Item>
              <Group label="Configure">
                <Item to={`/servers/${id}/general`} icon="general">General</Item>
                <Item to={`/servers/${id}/apis`} icon="definition">API definitions</Item>
                <Item to={`/servers/${id}/tools`} icon="tools">Tools</Item>
                <Item to={`/servers/${id}/targets`} icon="connections">Target APIs</Item>
                <Item to={`/servers/${id}/access`} icon="lock">Access control</Item>
                <Item to={`/servers/${id}/execution`} icon="shape">Execution</Item>
                <Item to={`/servers/${id}/observability`} icon="pulse">Observability</Item>
              </Group>
              <Group label="Deliver">
                <Item to={`/servers/${id}/packaging`} icon="package">Packaging</Item>
                <Item to={`/servers/${id}/builds`} icon="builds">Builds and releases</Item>
              </Group>
              <Group label="History">
                <Item to={`/servers/${id}/changes`} icon="changes">Changes</Item>
                <Item to={`/servers/${id}/activity`} icon="activity">Activity</Item>
              </Group>
            </div>
          </>
        ) : (
          <>
            <div className="nav-card"><small>Workspace</small><span>{settings.workspace}</span></div>
            <div className="nav-links">
              <Item to="/" icon="home" end>All MCP Servers</Item>
              <Item to="/library" icon="library">Shared library</Item>
              <Item to="/builds" icon="builds">All builds</Item>
              <Item to="/activity" icon="activity">Activity</Item>
            </div>
          </>
        )}
        <div className="nav-foot">
          <div className="nav-buttons" aria-label="For developers">
            <NavLink to="/agents" end className={({ isActive }) => 'nav-button' + (isActive && !location.search.includes('connect') ? ' active' : '')}>
              <Icon name="book" />API reference</NavLink>
            <Link to="/agents?connect" className={'nav-button' + (location.pathname === '/agents' && location.search.includes('connect') ? ' active' : '')}>
              <Icon name="zap" />Connect an agent</Link>
            <NavLink to="/cli" className={({ isActive }) => 'nav-button' + (isActive ? ' active' : '')}>
              <Icon name="terminal" />Command line</NavLink>
          </div>
          <div className="nav-who"><Link to="/account" title="Your account">{me.name}</Link><button type="button" onClick={signOut}>Sign out</button></div>
          <AboutLine build={settings.build} />
        </div>
      </nav>
      <div className="main">{children}</div>
    </div>
  )
}
