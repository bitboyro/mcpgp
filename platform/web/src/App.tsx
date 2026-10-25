import type { ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation, useParams } from 'react-router-dom'
import { AccessControlPage } from './pages/AccessControl'
import { ActivityPage } from './pages/Activity'
import { AgentsPage } from './pages/Agents'
import { ApiDefinitionsPage } from './pages/ApiDefinitions'
import { AllBuildsPage, BuildRedirect, ServerBuildsPage } from './pages/Builds'
import { ExecutionPage } from './pages/Execution'
import { GeneralPage } from './pages/General'
import { LibraryPage } from './pages/Library'
import { ObservabilityPage } from './pages/Observability'
import { OverviewPage } from './pages/Overview'
import { PackagingPage } from './pages/Packaging'
import { AccountPage } from './pages/Passwords'
import { ChangesPage } from './pages/Changes'
import { ServersPage } from './pages/Servers'
import { SignInPage } from './pages/SignIn'
import { TargetApisPage } from './pages/TargetApis'
import { ToolsPage } from './pages/Tools'
import { RequireSession } from './session'
import { Shell } from './Shell'

function Signed({ children }: { children: ReactNode }) {
  return <RequireSession><Shell>{children}</Shell></RequireSession>
}

/** A server page that moved in the 0.4.0 regrouping: old links and bookmarks still land on it. */
function Moved({ to }: { to: string }) {
  const { id = '' } = useParams()
  const { search } = useLocation()
  return <Navigate to={`/servers/${encodeURIComponent(id)}/${to}${search}`} replace />
}

export function App() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignInPage />} />
      <Route path="/" element={<Signed><ServersPage /></Signed>} />
      <Route path="/library" element={<Signed><LibraryPage /></Signed>} />
      <Route path="/builds" element={<Signed><AllBuildsPage /></Signed>} />
      <Route path="/builds/:buildId" element={<Signed><BuildRedirect /></Signed>} />
      <Route path="/activity" element={<Signed><ActivityPage /></Signed>} />
      <Route path="/agents" element={<Signed><AgentsPage /></Signed>} />
      <Route path="/account" element={<Signed><AccountPage /></Signed>} />
      <Route path="/servers/:id" element={<Signed><OverviewPage /></Signed>} />
      <Route path="/servers/:id/general" element={<Signed><GeneralPage /></Signed>} />
      <Route path="/servers/:id/apis" element={<Signed><ApiDefinitionsPage /></Signed>} />
      <Route path="/servers/:id/tools" element={<Signed><ToolsPage /></Signed>} />
      <Route path="/servers/:id/targets" element={<Signed><TargetApisPage /></Signed>} />
      <Route path="/servers/:id/access" element={<Signed><AccessControlPage /></Signed>} />
      <Route path="/servers/:id/execution" element={<Signed><ExecutionPage /></Signed>} />
      <Route path="/servers/:id/observability" element={<Signed><ObservabilityPage /></Signed>} />
      <Route path="/servers/:id/packaging" element={<Signed><PackagingPage /></Signed>} />
      <Route path="/servers/:id/builds" element={<Signed><ServerBuildsPage /></Signed>} />
      <Route path="/servers/:id/changes" element={<Signed><ChangesPage /></Signed>} />
      <Route path="/servers/:id/activity" element={<Signed><ActivityPage /></Signed>} />
      <Route path="/servers/:id/sources" element={<Moved to="apis" />} />
      <Route path="/servers/:id/connections" element={<Moved to="targets" />} />
      <Route path="/servers/:id/safety" element={<Moved to="execution" />} />
      <Route path="/servers/:id/package" element={<Moved to="packaging" />} />
      <Route path="*" element={<Signed><div className="empty">There is no page here.</div></Signed>} />
    </Routes>
  )
}
