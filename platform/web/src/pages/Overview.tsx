import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError, type Build, type Diagnostic, type Manifest, type Tool } from '../api'
import { access, diffLines } from '../choices'
import { AUTH_LABELS, EXPOSURE, counts, listedTools, useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Icon, Loading, PageHead, Segmented, ago } from '../ui'

type Tab = 'agent' | 'customer' | 'package' | 'checks'
type IconName = Parameters<typeof Icon>[0]['name']

export function BuildButton({ id, disabled = false }: { id: string; disabled?: boolean }) {
  const { can } = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const start = useMutation({
    mutationFn: () => api.startBuild(id),
    onSuccess: async (build) => {
      await queryClient.invalidateQueries({ queryKey: ['builds', id] })
      navigate(`/servers/${id}/builds?build=${build.id}`)
    },
  })
  if (!can('builds:run')) return null
  return (
    <>
      {start.error && <ErrorBox error={start.error} />}
      <button type="button" className="btn btn-primary" onClick={() => start.mutate()} disabled={disabled || start.isPending}><Icon name="package" />{start.isPending ? 'Starting…' : 'Build'}</button>
    </>
  )
}

type State = 'ok' | 'warn' | 'idle'

interface Area {
  key: string; title: string; icon: IconName; purpose: string; state: State; status: string; summary: string; to: string
  /** Shown as the page's next step when this is the most important thing left. */
  next?: string
}

const SIGN_IN: Record<string, string> = { none: 'No sign-in', static: 'API keys', oauth: 'OAuth' }

/** One card per area of the server (design/ui.md, Overview): what it does, how it's set up, and a link to it. */
export function areas(id: string, m: Manifest, build: Build | undefined, compileError: boolean): Area[] {
  const path = (p: string) => `/servers/${id}/${p}`
  const errors = counts(m).error
  const apiTools = m.tools.filter((t) => t.kind === 'api').length
  const excluded = m.diagnostics.filter((d) => d.code === 'OPERATION_EXCLUDED').length
  const http = m.runtime.transports?.allowed.includes('http') ?? false
  const signIn = m.runtime.inboundAuth?.default ?? 'none'
  const rd = m.policies.runtimeDefaults ?? {}
  const scopes = Object.keys(rd.configuration?.server?.auth?.scopes ?? {}).length
  const upstream = [...new Set(m.apis.flatMap((a) => a.upstreamAuth?.supported ?? []))].map((a) => AUTH_LABELS[a] ?? a)
  const levels = [...new Set(m.apis.map((a) => access(m, a.id)))].map((l) => ({ 'read-only': 'read only', 'read-write': 'read and write', all: 'everything' }[l] ?? l))
  return [
    {
      key: 'apis', title: 'API definitions', icon: 'definition', purpose: 'The OpenAPI documents the server is built from.', to: path('apis'),
      ...(compileError || errors > 0
        ? { state: 'warn' as State, status: compileError ? "Doesn't compile" : `${errors} error${errors === 1 ? '' : 's'}`, next: 'fix the API definition errors' }
        : { state: 'ok' as State, status: 'Valid' }),
      summary: m.apis.map((a) => `${a.title ?? a.id} · ${a.contracts.map((c) => c.version).join(', ')}`).join('; '),
    },
    {
      key: 'tools', title: 'Tools', icon: 'tools', purpose: 'What connecting agents see and can call.', to: path('tools'),
      state: apiTools > 0 ? 'ok' : 'warn', status: `${apiTools} of ${apiTools + excluded} operations`,
      ...(apiTools === 0 ? { next: 'choose which operations become tools' } : {}),
      summary: [EXPOSURE[m.exposure.mode] ?? m.exposure.mode, levels.join(' / '), m.packs.length ? `${m.packs.length} support pack${m.packs.length === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · '),
    },
    {
      key: 'targets', title: 'Target APIs', icon: 'connections', purpose: 'The APIs it calls and how it signs in to them.', to: path('targets'),
      state: 'ok', status: m.instances.mode === 'multi' ? 'Several target servers' : 'One target server',
      summary: upstream.length ? upstream.join(', ') : 'No sign-in declared',
    },
    {
      key: 'access', title: 'Access control', icon: 'lock', purpose: 'Who can connect and what they may do.', to: path('access'),
      ...(http && signIn === 'none'
        ? { state: 'warn' as State, status: 'No sign-in by default', next: 'choose how clients sign in before sharing this server' }
        : { state: 'ok' as State, status: http ? `Sign-in: ${SIGN_IN[signIn] ?? signIn}` : 'Local use (stdio)' }),
      summary: [`Offers ${(m.runtime.inboundAuth?.allowed ?? ['none', 'static', 'oauth']).map((a) => SIGN_IN[a] ?? a).join(', ')}`,
        scopes ? `${scopes} scope rule${scopes === 1 ? '' : 's'}` : ''].filter(Boolean).join(' · '),
    },
    {
      key: 'execution', title: 'Execution', icon: 'shape', purpose: 'How calls run: confirmations and limits.', to: path('execution'),
      ...(m.policies.confirmDestructive === false ? { state: 'warn' as State, status: 'Changes run without confirmation' } : { state: 'ok' as State, status: 'Confirms changes' }),
      summary: [m.policies.rateLimits?.enabled === false ? 'no rate limit' : `${m.policies.rateLimits?.perMinute ?? 120} calls/min per target`,
        m.policies.batch?.enabled ? 'batching on' : 'batching off', (m.policies.results?.mode ?? 'inline') === 'inline' ? '' : 'large results kept'].filter(Boolean).join(' · '),
    },
    {
      key: 'observability', title: 'Observability', icon: 'pulse', purpose: 'What the delivered server records.', to: path('observability'),
      ...(rd.audit || rd.telemetry || rd.configuration?.logging ? { state: 'ok' as State, status: 'Set' } : { state: 'idle' as State, status: 'Package defaults' }),
      summary: [rd.audit?.file?.enabled === false ? 'audit file off' : `audit ${rd.audit?.file?.retainDays ?? 30} days`, rd.telemetry?.enabled ? 'telemetry on' : 'telemetry off'].join(' · '),
    },
    {
      key: 'delivery', title: 'Delivery', icon: 'package', purpose: "How it's packaged and runs for customers.", to: path('packaging'),
      ...(!build ? { state: 'idle' as State, status: 'Not built yet', next: 'build the package' }
        : build.status === 'failed' ? { state: 'warn' as State, status: 'Last build failed', next: 'look at why the last build failed' }
          : build.status === 'succeeded' ? { state: 'ok' as State, status: `Built ${ago(build.finished ?? build.created)}` }
            : { state: 'idle' as State, status: 'Building' }),
      summary: (m.runtime.transports?.allowed ?? ['stdio']).map((t) => t === 'http' ? 'HTTP' : t).join(' and '),
    },
    {
      key: 'general', title: 'General', icon: 'general', purpose: 'Name and the instructions agents receive.', to: path('general'),
      ...(m.server.instructions ? { state: 'ok' as State, status: 'Has instructions' } : { state: 'warn' as State, status: 'No instructions', next: 'write instructions for agents' }),
      summary: `${m.server.name} ${m.server.version}`,
    },
  ]
}

/** The order in which unfinished areas become the page's one next step. */
const NEXT_ORDER = ['apis', 'tools', 'access', 'delivery', 'general']

export function OverviewPage() {
  const { id = '' } = useParams()
  const server = useServer(id)
  const manifest = useManifest(id, 'latest', server.data?.latestVersion != null)
  const builds = useQuery({ queryKey: ['builds', id], queryFn: () => api.builds(id), enabled: !!server.data })
  const compileError = manifest.error instanceof ApiError ? manifest.error : null
  const latest = server.data?.versions.at(-1)
  const lastBuild = [...(builds.data?.items ?? [])].sort((a, b) => b.created.localeCompare(a.created))[0]
  const list = manifest.data ? areas(id, manifest.data, lastBuild, !!compileError) : []
  const next = NEXT_ORDER.map((k) => list.find((a) => a.key === k && a.next)).find(Boolean)

  return (
    <>
      <PageHead title={manifest.data?.server.title ?? server.data?.title ?? id}
        sub={latest ? `Revision ${latest.number} · saved by ${latest.author} ${ago(latest.created)}` : undefined}>
        {server.data?.system
          ? <Link to="/agents?connect" className="btn btn-primary"><Icon name="zap" />Connect an agent</Link>
          : <Link to={`/servers/${id}/apis`} className="btn"><Icon name="upload" />Upload API definition</Link>}
        {manifest.data && <Link to={`/servers/${id}/preview`} className="btn"><Icon name="eye" />Preview as agent</Link>}
        <BuildButton id={id} />
      </PageHead>
      <div className="body" style={{ flexDirection: 'column', alignItems: 'stretch' }}>
        <ErrorBox error={server.error} />
        {server.isLoading && <Loading />}
        {server.data?.system && (
          <div className="card" aria-label="Made by the platform">
            <h2>Made by the platform</h2>
            <p className="hint">The platform's API as tools, for your agents. The platform makes this server and adds a revision with every
              release, so it can't be changed here. To change what your agents get (hide tools, add notices), make a variant of it on
              {' '}<Link to="/">All servers</Link>: it follows every new revision.</p>
          </div>
        )}
        {compileError && (
          <div className="card">
            <h2>This revision doesn't compile</h2>
            <p className="hint">{compileError.message}. Validation on <Link to={`/servers/${id}/apis`}>API definitions</Link> says why.</p>
          </div>
        )}
        {next && !server.data?.system && (
          <Link to={next.to} className="next-step">Next: {next.next} →</Link>
        )}
        {list.length > 0 && (
          <section className="area-grid" aria-label="Areas">
            {list.map((a) => (
              <Link key={a.key} to={a.to} className="area-card" aria-label={`${a.title}: ${a.status}`}>
                <span className="area-title"><Icon name={a.icon} size={18} />{a.title}</span>
                <span className="hint">{a.purpose}</span>
                <span className={'chip chip-' + (a.state === 'ok' ? 'ok' : a.state === 'warn' ? 'warn' : 'idle')}>{a.status}</span>
                <span className="area-summary">{a.summary}</span>
                <span className="area-open">Open {a.title} →</span>
              </Link>
            ))}
          </section>
        )}
      </div>
    </>
  )
}

const TABS: [Tab, string][] = [['agent', 'Agent'], ['customer', 'Customer setup'], ['package', 'Package'], ['checks', 'Validation']]

/** What the latest revision gives agents and customers. The tab is in the address, so a view can be linked. */
export function PreviewPage() {
  const { id = '' } = useParams()
  const server = useServer(id)
  const manifest = useManifest(id, 'latest', server.data?.latestVersion != null)
  const [params, setParams] = useSearchParams()
  const tab = TABS.find(([key]) => key === params.get('tab'))?.[0] ?? 'agent'
  const compileError = manifest.error instanceof ApiError ? manifest.error : null
  return (
    <>
      <PageHead title="Preview" sub="What the latest revision gives agents and customers.">
        <Link to={`/servers/${id}`} className="btn">Back to overview</Link>
      </PageHead>
      <div className="body" style={{ flexDirection: 'column', flexWrap: 'nowrap', alignItems: 'stretch' }}>
        <ErrorBox error={server.error ?? (compileError ? null : manifest.error)} />
        {(server.isLoading || manifest.isLoading) && <Loading />}
        {compileError && (
          <div className="card">
            <h2>This revision doesn't compile</h2>
            <p className="hint">{compileError.message}. Validation on <Link to={`/servers/${id}/apis`}>API definitions</Link> says why.</p>
          </div>
        )}
        {manifest.data && (
          <section className="stage" aria-label="Preview">
            <div className="card-head">
              <Segmented role="tab" label="Preview" value={tab} options={TABS}
                onChange={(next) => setParams(next === 'agent' ? {} : { tab: next }, { replace: true })} />
            </div>
            {tab === 'agent' && <AgentPreview manifest={manifest.data} />}
            {tab === 'customer' && <CustomerPreview id={id} />}
            {tab === 'package' && <PackagePreview id={id} manifest={manifest.data} />}
            {tab === 'checks' && <Checks diagnostics={manifest.data.diagnostics ?? compileDiagnostics(compileError)} />}
          </section>
        )}
      </div>
    </>
  )
}

function compileDiagnostics(error: ApiError | null): Diagnostic[] {
  return error ? [{ level: 'error', code: error.code, message: error.message }] : []
}

export function DefinitionDiff({ id, version, after }: { id: string; version: number; after?: string }) {
  const detail = useQuery({ queryKey: ['version', id, version], queryFn: () => api.version(id, version) })
  const root = detail.data?.root
  const before = useQuery({ queryKey: ['file', id, version, root], queryFn: () => api.file(id, version, root!), enabled: !!root })
  if (!before.data || after === undefined) return <Loading what="the changes" />
  const lines = diffLines(before.data, after)
  return (
    <>
      <div>
        <div className="eyebrow">What gets saved</div>
        <h3 className="mono">{root}</h3>
      </div>
      <pre className="code" aria-label="Changes to the definition">
        {lines.map((l, i) => (
          <span key={i} style={{ display: 'block', color: l.kind === '+' ? 'var(--note-ok-ink)' : l.kind === '-' ? 'var(--note-bad-ink)' : undefined }}>
            {l.kind === '…' ? '…' : `${l.kind} ${l.text}`}
          </span>
        ))}
      </pre>
      <p className="hint">Only these lines change; the rest of the file, its comments and the other files stay as they are.</p>
    </>
  )
}

export function agentView(tool: Tool) {
  return { name: tool.name, title: tool.title, description: tool.description, inputSchema: tool.inputSchema, annotations: tool.annotations }
}

function AgentPreview({ manifest }: { manifest: Manifest }) {
  const listed = listedTools(manifest)
  const first = listed.find((t) => t.kind === 'api') ?? listed[0]
  return (
    <>
      <div style={{ display: 'flex', gap: 14, alignItems: 'baseline', flexWrap: 'wrap' }}>
        <span className="big">{listed.length}</span>
        <span className="hint">tools in tools/list · {manifest.tools.length} in all · {EXPOSURE[manifest.exposure.mode] ?? manifest.exposure.mode}</span>
      </div>
      {manifest.server.instructions && <div className="stage-card"><span className="eyebrow">Instructions</span><p>{manifest.server.instructions}</p></div>}
      <div className="list">
        {listed.map((t) => (
          <div key={t.name} className="stage-card" style={{ flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'space-between', alignItems: 'center' }}>
            <span className="mono" style={{ color: 'var(--stage-bright)', fontSize: 14 }}>{t.name}</span>
            <span className="meta">{t.title}</span>
          </div>
        ))}
      </div>
      {first && <pre className="code" aria-label={`${first.name} as the agent gets it`}>{JSON.stringify(agentView(first), null, 2)}</pre>}
    </>
  )
}

export function CustomerPreview({ id, version = 'latest', text }: { id: string; version?: string | number; text?: string }) {
  const saved = useQuery({ queryKey: ['example-config', id, String(version)], queryFn: () => api.exampleConfig(id, version), retry: false, enabled: text === undefined })
  const example = text === undefined ? saved : { data: text, error: null }
  return (
    <>
      <div className="card-head">
        <div>
          <div className="eyebrow">Your customer will configure</div>
          <h3>The example configuration in the package</h3>
        </div>
        <span className="example">Example data</span>
      </div>
      <ErrorBox error={example.error} />
      {example.data && <pre className="code">{example.data}</pre>}
      <p className="hint">Hosts and names are placeholders; the customer puts in their own. Secrets are only references to their .env.</p>
    </>
  )
}

function PackagePreview({ id, manifest }: { id: string; manifest: Manifest }) {
  const builds = useQuery({ queryKey: ['builds', id], queryFn: () => api.builds(id) })
  const last = builds.data?.items.find((b) => b.status === 'succeeded' && !b.deleted)
  const files = useQuery({ queryKey: ['artifacts', last?.id], queryFn: () => api.artifacts(last!.id), enabled: !!last })
  const name = `${manifest.server.name}-${manifest.server.version}`
  return (
    <>
      <div>
        <div className="eyebrow">What you hand to your customer</div>
        <h3 className="mono">{last ? last.artifacts.find((a) => a.name.endsWith('.zip'))?.name : name + '.zip'}</h3>
      </div>
      {!last && <p className="hint">Not built yet. Build to see the package's files, signed and checksummed.</p>}
      {files.data?.packageFiles && <pre className="code">{files.data.packageFiles.map((f) => f.path).join('\n')}</pre>}
      <div className="note note-ok">Works fully offline: no license call, no telemetry to you, no downloads at runtime.</div>
      {last && <Link to={`/servers/${id}/builds?build=${last.id}`}>Open build {last.serverVersion} →</Link>}
    </>
  )
}

export function Checks({ diagnostics }: { diagnostics: Diagnostic[] }) {
  const c = counts({ diagnostics } as Manifest)
  const shown = diagnostics.filter((d) => d.level !== 'info')
  return (
    <>
      <div className={'note ' + (c.error ? 'note-bad' : c.warning ? 'note-warn' : 'note-ok')}>
        {c.error ? `${c.error} error${c.error === 1 ? '' : 's'}` : 'Compiles'}{c.warning ? ` · ${c.warning} warning${c.warning === 1 ? '' : 's'}` : ''} · {c.info} notes
      </div>
      <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
        {shown.map((d, i) => (
          <li key={i} className="stage-card">
            <span className="mono" style={{ fontSize: 12, color: d.level === 'error' ? 'var(--note-bad-ink)' : 'var(--note-warn-ink)' }}>{d.level} {d.code}</span>
            <span>{d.message}</span>
            {d.location && <span className="meta mono">{typeof d.location === 'string' ? d.location : `${d.location.file ?? ''}${d.location.pointer ? '#' + d.location.pointer : ''}`}</span>}
          </li>
        ))}
      </ul>
      {c.info > 0 && <p className="hint">{c.info} notes (for example operations left out on purpose) are on API definitions.</p>}
    </>
  )
}
