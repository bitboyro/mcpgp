import type { ReactNode } from 'react'
import { Route, Routes } from 'react-router-dom'
import { ActivityPage } from './pages/Activity'
import { AgentsPage } from './pages/Agents'
import { AllBuildsPage, ServerBuildsPage } from './pages/Builds'
import { ConnectionsPage } from './pages/Connections'
import { SafetyPage } from './pages/Safety'
import { LibraryPage } from './pages/Library'
import { OverviewPage } from './pages/Overview'
import { PackagePage } from './pages/Package'
import { AccountPage } from './pages/Passwords'
import { ChangesPage } from './pages/Changes'
import { ServersPage } from './pages/Servers'
import { SignInPage } from './pages/SignIn'
import { SourcesPage } from './pages/Sources'
import { ToolsPage } from './pages/Tools'
import { RequireSession } from './session'
import { Shell } from './Shell'

function Signed({ children }: { children: ReactNode }) {
  return <RequireSession><Shell>{children}</Shell></RequireSession>
}

export function App() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignInPage />} />
      <Route path="/" element={<Signed><ServersPage /></Signed>} />
      <Route path="/library" element={<Signed><LibraryPage /></Signed>} />
      <Route path="/builds" element={<Signed><AllBuildsPage /></Signed>} />
      <Route path="/activity" element={<Signed><ActivityPage /></Signed>} />
      <Route path="/agents" element={<Signed><AgentsPage /></Signed>} />
      <Route path="/account" element={<Signed><AccountPage /></Signed>} />
      <Route path="/servers/:id" element={<Signed><OverviewPage /></Signed>} />
      <Route path="/servers/:id/tools" element={<Signed><ToolsPage /></Signed>} />
      <Route path="/servers/:id/connections" element={<Signed><ConnectionsPage /></Signed>} />
      <Route path="/servers/:id/safety" element={<Signed><SafetyPage /></Signed>} />
      <Route path="/servers/:id/sources" element={<Signed><SourcesPage /></Signed>} />
      <Route path="/servers/:id/changes" element={<Signed><ChangesPage /></Signed>} />
      <Route path="/servers/:id/package" element={<Signed><PackagePage /></Signed>} />
      <Route path="/servers/:id/builds" element={<Signed><ServerBuildsPage /></Signed>} />
      <Route path="/servers/:id/activity" element={<Signed><ActivityPage /></Signed>} />
      <Route path="*" element={<Signed><div className="empty">There is no page here.</div></Signed>} />
    </Routes>
  )
}
