// The platform API (docs/spec/platform-api.md), as the web UI uses it. The session is an HttpOnly
// cookie; every request says it comes from the web client, which the platform requires for changes.

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  /** The whole problem document: extra fields like `missing` or `candidates` for SPEC_PROBLEMS. */
  readonly body: Record<string, unknown>

  constructor(status: number, code: string, detail: string, body: Record<string, unknown> = {}) {
    super(detail)
    this.status = status
    this.code = code
    this.body = body
  }
}

export const SIGNED_OUT = 'mcpgp:signed-out'

async function send(method: string, path: string, body?: unknown, contentType?: string): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', 'X-Mcpgp-Client': 'web' }
  let payload: BodyInit | undefined
  if (body !== undefined) {
    if (body instanceof Blob || body instanceof ArrayBuffer) {
      payload = body
      headers['Content-Type'] = contentType ?? 'application/zip'
    } else {
      payload = JSON.stringify(body)
      headers['Content-Type'] = 'application/json'
    }
  }
  const response = await fetch('/api/v1' + path, { method, headers, body: payload, credentials: 'same-origin' })
  if (!response.ok) {
    let code = 'ERROR'
    let detail = response.statusText || String(response.status)
    let problem: Record<string, unknown> = {}
    try {
      problem = await response.json() as Record<string, unknown>
      code = (problem.code as string | undefined) ?? code
      detail = (problem.detail as string | undefined) ?? detail
    } catch {
      // not a problem document
    }
    if (response.status === 401 && path !== '/sessions') window.dispatchEvent(new Event(SIGNED_OUT))
    throw new ApiError(response.status, code, detail, problem)
  }
  return response
}

async function json<T>(method: string, path: string, body?: unknown, contentType?: string): Promise<T> {
  return (await send(method, path, body, contentType)).json() as Promise<T>
}

async function text(path: string): Promise<string> {
  return (await send('GET', path)).text()
}

const q = (params: Record<string, string | number | boolean | undefined>) => {
  const search = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') search.set(k, String(v))
  const s = search.toString()
  return s ? '?' + s : ''
}

const enc = encodeURIComponent

/** How an auto instance learns which contract version its target runs (runtime-config §4.4). */
export interface Detect {
  method: string; path: string; pattern: string; field?: string; header?: string; underBasePath?: boolean
  onFailure?: 'useDefault' | 'refuse'
}

export interface Items<T> { items: T[] }

export interface Identity {
  id: string
  kind: 'person' | 'agent' | 'pipeline'
  name: string
  scopes: string[]
  created: string
  disabled: boolean
  tokenScopes?: string[]
  signsInWith?: 'oidc' | 'ldap'
  rolesFromGroups?: boolean
  /** People: whether they have a local password; whether it's a temporary one to replace; until when wrong passwords locked them. */
  password?: boolean
  mustChangePassword?: boolean
  lockedUntil?: string
}

/** One change between two builds: `impact` breaking (agents or clients of the earlier build need updating), notable or info. */
export interface VersionChange { impact: 'breaking' | 'notable' | 'info'; tool: string | null; kind: string; text: string }

export interface VersionDiff {
  from: { version: number; serverVersion: string; released: boolean }
  to: { version: number; serverVersion: string }
  /** The version of the last released build, if there is one. */
  released: number | null
  summary: string
  breaking: number
  changes: VersionChange[]
  markdown: string
}

export interface Token {
  id: string
  identity: string
  scopes: string[]
  label: string | null
  created: string
  expires: string | null
  revoked: boolean
  value?: string
  lastUsed?: string | null
}

export interface VersionRef {
  number: number
  digest: string
  message?: string
  author: string
  created: string
}

export interface ServerSummary {
  id: string
  title: string
  parent?: string
  archived: boolean
  latestVersion: number | null
  updated: string
  /** The platform's own MCP server: made and updated by the platform; read it, make variants of it. */
  system?: boolean
}

export interface Server extends ServerSummary {
  created: string
  versions: VersionRef[]
}

export interface VersionDetail extends VersionRef {
  root: string
  files: { path: string; sha256: string }[]
}

export interface Diagnostic {
  level: 'error' | 'warning' | 'info'
  code: string
  message: string
  location?: string | { file?: string; pointer?: string }
}

export interface Artifact { name: string; size: number; sha256: string }

export interface Build {
  id: string
  server: string
  version: number
  status: 'queued' | 'running' | 'succeeded' | 'failed'
  startedBy: string
  created: string
  started: string | null
  finished: string | null
  serverVersion: string | null
  released: boolean
  releasedBy?: string
  archived: boolean
  deleted: boolean
  artifacts: Artifact[]
  error?: string
}

export interface Approval {
  id: string
  action: string
  target: string
  requestedBy: string
  requestedAt: string
  status: 'pending' | 'approved' | 'rejected'
  decidedBy: string | null
  decidedAt: string | null
  comment: string | null
}

export interface Activity {
  ts: string
  actor: string
  actorKind: string
  action: string
  target: string
  outcome: string
  details: Record<string, unknown>
}

export interface LibraryItem {
  kind: string
  name: string
  title: string
  reference: string
  versions: VersionRef[]
  updated: string
  /** Naming policies: the policy as data, and the servers that extend it (`pinned`: the version they pin, null for the latest). */
  policy?: NamingPolicy | null
  /** Shared notices: the set as data; `usedBy` lists the servers that include it. */
  set?: NoticeSet | null
  root?: string
  usedBy?: { server: string; pinned: string | null }[]
}

export interface LibraryEditPreview { base: number; text: string; problems: string[]; usedBy: { server: string; pinned: string | null }[] }

export interface SignInOptions { local: boolean; ldap: boolean; oidc: boolean; oidcLabel?: string }

export interface Settings {
  workspace: string
  auth: SignInOptions
  signing: { configured: boolean }
  retention: { archiveAfterDays: number; deleteArchivedAfterDays: number; keepLast: number }
  guardrails: { agentBuildsPerHour: number; agentVersionsPerHour: number }
}

// The manifest (docs/spec/manifest.md): only the parts the UI shows are typed.
export interface ProvenanceStep { step: string; value?: unknown; file?: string; pointer?: string; op?: string; since?: string }

export interface Tool {
  name: string
  title?: string
  description?: string
  kind: 'api' | 'pack' | 'builtin' | 'custom'
  api?: string
  pack?: string
  handler?: string
  annotations?: { readOnlyHint?: boolean; destructiveHint?: boolean; idempotentHint?: boolean; openWorldHint?: boolean }
  confirm?: boolean
  sensitive?: boolean
  operation?: { key: string; operationId?: string; tags?: string[] }
  inputSchema?: unknown
  provenance?: Record<string, ProvenanceStep[]>
  variants?: Record<string, { method: string; path: string; security?: { scheme: string; scopes?: string[] }[][] }>
}

export interface Contract { version: string; format: string; basePath?: string; default: boolean; digest: string }

export interface ManifestApi {
  id: string
  title?: string
  toolPrefix?: string
  contracts: Contract[]
  detect?: Detect
  downstreamAuth?: { supported: string[]; identity?: string; unsatisfiedTools?: string; apiKey?: { in: string; name: string }; fromRequest?: { header?: string }; tokenExchange?: Record<string, never> }
}

export interface Manifest {
  manifestVersion: string
  server: { name: string; title?: string; version: string; instructions?: string }
  build: { extends?: { name: string; version?: string } | null; definition?: { digest: string } }
  apis: ManifestApi[]
  tools: Tool[]
  exposure: { mode: string; discoveryStyle?: string; pinned?: string[] }
  instances: { mode: 'single' | 'multi'; mixedContracts?: string; max?: number | null }
  packs: { id: string; version: string; tools: string[] }[]
  policies: {
    confirmDestructive?: boolean; responseLimitKb?: number; redaction?: { fields: string[] }
    runtimeDefaults?: { audit?: { file?: { retainDays?: number; rotate?: string; enabled?: boolean }; arguments?: string } }
  }
  runtime: { transports?: { allowed: string[]; default: string }; inboundAuth?: { allowed: string[]; default: string } }
  diagnostics: Diagnostic[]
}

export interface PackageFile { path: string; size: number }

export interface Change { op: 'set' | 'remove'; path: string; value?: unknown }

/** An API's `select` block (definition-format.md §4). */
export interface Select {
  access?: 'read-only' | 'read-write' | 'all'
  include?: Selector | Selector[]
  exclude?: Selector | Selector[]
  deprecated?: 'hide' | 'show' | 'mark'
  [key: string]: unknown
}

export type Selector = Record<string, unknown>

export interface Operation {
  key: string
  method: string
  path: string
  operationId?: string
  summary?: string
  deprecated: boolean
  tags: string[]
  contracts: string[]
  state: 'tool' | 'resource' | 'excluded' | 'hidden'
  tool?: string
  exclusion?: { kind: 'access' | 'include' | 'exclude' | 'deprecated'; reason: string; index?: number; selector?: Selector }
}

/** A tool call against a mock of the API (spec/platform-api.md §2.1). */
export interface TryResult {
  result?: { isError?: boolean; content?: { type: string; text?: string }[]; structuredContent?: unknown; _meta?: Record<string, unknown> }
  error?: { code: number; message: string }
  requests: { method: string; path: string; query?: string | null; headers: Record<string, string>; body: string; status: number }[]
  leftOut: string[]
  /** Set when the server doesn't list the tool (hybrid or discovery exposure): the discovery tool it was called through. */
  via?: string
}

/** A notice added to tool descriptions (definition-format.md §5.1). */
export interface Notice { id: string; text: string; position?: 'append' | 'prepend'; match?: Selector }

/** A rule of the definition (definition-format.md §5), as far as the pages read and write it. */
export interface Rule {
  id?: string
  match?: Selector
  title?: { set?: string }
  description?: { set?: string }
  params?: Record<string, { description?: { set?: string } }>
  [key: string]: unknown
}

/** The definition as data: the root file as written, and the result with what it extends and includes. */
export interface DefinitionData {
  version?: number
  root: { descriptions?: { include?: string | string[]; notices?: Notice[] }; overrides?: { rules?: Rule[] }; [key: string]: unknown }
  resolved: { descriptions?: { notices?: Notice[] }; [key: string]: unknown }
  /** Notices from `descriptions.include`: notice id → the shared item, `mcpgp://acme/notices/company-policy@1`. */
  noticeSources?: Record<string, string>
}

/** What a new server from a spec would be (a dry run of POST /servers/from-spec). */
export interface FromSpecPlan {
  id: string; title: string; api: string; version: string; main: string; format: string
  files: string[]; tools: number; exists: boolean; diagnostics: Diagnostic[]
  /** Files picked without their folders, put where the spec refers to them. */
  moved: { from: string; to: string }[]
}

/** What adding a contract version would do (a dry run of POST /servers/{id}/contracts). */
export interface ContractPlan {
  api: string; version: string; spec: string; replaces: boolean; compiles: boolean; diagnostics: Diagnostic[]; moved: { from: string; to: string }[]
  /** The same spec as the saved one: saving makes no version. */
  unchanged?: boolean
  /** Fetched from an address: where from, and what came. */
  source?: string; fetched?: { url: string; file: string; bytes: number }
  /** What agents would notice, as on the Changes page. */
  summary?: string; breaking?: number; changes?: { impact: 'breaking' | 'notable' | 'info'; tool: string | null; kind: string; text: string }[]
}

/** Where a contract's spec comes from: uploaded files, or fetched from an address (from an allowed host). */
export type SpecSource = Record<string, string> | Blob | { url: string }

const specBody = (source: SpecSource) => source instanceof Blob ? source : 'url' in source ? undefined : { files: source }
const specUrl = (source: SpecSource) => !(source instanceof Blob) && 'url' in source && typeof source.url === 'string' ? source.url : undefined

/** The extra fields of a SPEC_PROBLEMS answer: what the upload lacks. */
/** Operations that would get the same tool name (NAME_COLLISIONS): each later one, with the name it would get from its method and path. */
export interface NameClash { operation: string; method: string; path: string; operationId?: string; name: string; suggested: string; base: string }

export interface SpecProblems {
  missing?: { file: string; from: string; pointer: string }[]
  /** References to a part of a file that isn't there: `file` is `api.json#/definitions/Entry`. */
  broken?: { file: string; from: string; pointer: string }[]
  remote?: { file: string; from: string; pointer: string }[]
  candidates?: string[]
  problems?: string[]
}

/** A set of shared notices, the library kind `notices` (definition-format.md §5.1). */
export interface NoticeSet { title?: string; notices: Notice[] }

/** What applying shared notices did to one server. */
export interface ApplyResult { server: string; status: 'updated' | 'unchanged' | 'inherited' | 'failed'; version?: number; from?: string; error?: string }

/** What a build of a version will contain, before building it (packaging.md). */
export interface PackagePreview {
  version?: number
  zip: string
  formats: { id: 'jar' | 'mcpb' | 'docker'; file: string }[]
  files: string[]
  docs: Record<string, string>
  warnings: string[]
  signed: boolean
}

/** A version's naming (naming.md): the policy it extends, its own settings, the merged result, the lock. */
export interface NamingInfo {
  version?: number
  extends: string | null
  own: NamingPolicy
  effective: NamingPolicy
  lock: { present: boolean; entries: { id: string; name: string; since?: string }[] }
  /** Each tool's lock id (the operation key, plus `#<suffix>` for content-type variants). */
  tools?: { name: string; id: string }[]
}

export interface NamingPolicy {
  source?: string
  fallback?: string
  normalize?: { split?: string[]; dropWords?: string[] }
  shape?: { order?: string; case?: string; number?: string }
  vocabulary?: Record<string, string>
  methodVerbs?: Record<string, string>
  namespace?: { server?: string; suffix?: string; perApi?: boolean }
  limits?: { profile?: string; onTooLong?: string }
  collisions?: string
  [key: string]: unknown
}

export interface OperationList {
  version?: number
  apis: { id: string; title: string; select: Select; operations: Operation[] }[]
}

export interface EditPreview {
  base: number
  digest: string
  definition: string
  manifest?: Manifest
  exampleConfig?: string
  diagnostics?: Diagnostic[]
  operations?: OperationList
}

export const api = {
  signInOptions: () => json<{ auth: SignInOptions; publicUrl?: string | null }>('GET', '/platform'),
  signIn: (name: string, password: string) => json<Token>('POST', '/sessions', { name, password }),
  signOut: () => json<{ signedOut: boolean }>('DELETE', '/sessions/current'),
  me: () => json<Identity>('GET', '/me'),
  settings: () => json<Settings>('GET', '/settings'),

  servers: (archived = false) => json<Items<ServerSummary>>('GET', '/servers' + q({ archived: archived || undefined })),
  server: (id: string) => json<Server>('GET', `/servers/${enc(id)}`),
  createServer: (id: string, files: Record<string, string> | Blob, title?: string) =>
    json<Server>('POST', '/servers' + q({ id, title }), files instanceof Blob ? files : { files }),
  /** A dry run says what would be created; problems with the upload are ApiError SPEC_PROBLEMS (see SpecProblems). */
  fromSpecPlan: (files: Record<string, string> | Blob, params: { id?: string; title?: string; version?: string; main?: string; collisions?: string; renames?: string }) =>
    json<FromSpecPlan>('POST', '/servers/from-spec' + q({ ...params, dryRun: true }), files instanceof Blob ? files : { files }),
  createFromSpec: (files: Record<string, string> | Blob, params: { id?: string; title?: string; version?: string; main?: string; collisions?: string; renames?: string }) =>
    json<Server>('POST', '/servers/from-spec' + q(params), files instanceof Blob ? files : { files }),
  contractPlan: (id: string, source: SpecSource, params: { api?: string; version?: string; main?: string; default?: boolean }) =>
    json<ContractPlan>('POST', `/servers/${enc(id)}/contracts` + q({ ...params, url: specUrl(source), dryRun: true }), specBody(source)),
  addContract: (id: string, source: SpecSource, params: { api?: string; version?: string; main?: string; default?: boolean }) =>
    json<Server>('POST', `/servers/${enc(id)}/contracts` + q({ ...params, url: specUrl(source) }), specBody(source)),
  addVersion: (id: string, files: Record<string, string> | Blob, message?: string) =>
    json<Server>('POST', `/servers/${enc(id)}/versions` + q({ message }), files instanceof Blob ? files : { files }),
  version: (id: string, version: string | number) => json<VersionDetail>('GET', `/servers/${enc(id)}/versions/${version}`),
  file: (id: string, version: string | number, path: string) =>
    text(`/servers/${enc(id)}/versions/${version}/files/${path.split('/').map(enc).join('/')}`),
  validate: (id: string, version: string | number) =>
    json<{ ok?: boolean; diagnostics: Diagnostic[] }>('POST', `/servers/${enc(id)}/versions/${version}/validate`),
  exampleConfig: (id: string, version: string | number = 'latest') => text(`/servers/${enc(id)}/versions/${version}/example-config`),
  manifest: (id: string, version: string | number = 'latest') => json<Manifest>('GET', `/servers/${enc(id)}/versions/${version}/manifest`),
  previewEdit: (id: string, base: number, changes: Change[], operations = false) =>
    json<EditPreview>('POST', `/servers/${enc(id)}/edits`, { base, changes, dryRun: true, operations: operations || undefined }),
  warmTry: (id: string, version: string | number) => send('POST', `/servers/${enc(id)}/versions/${version}/try/warm`),
  tryTool: (id: string, version: string | number, tool: string, args: unknown) =>
    json<TryResult>('POST', `/servers/${enc(id)}/versions/${version}/try`, { tool, arguments: args }),
  definition: (id: string, version: string | number = 'latest') => json<DefinitionData>('GET', `/servers/${enc(id)}/versions/${version}/definition`),
  packagePreview: (id: string, version: string | number = 'latest') => json<PackagePreview>('GET', `/servers/${enc(id)}/versions/${version}/package`),
  naming: (id: string, version: string | number = 'latest') => json<NamingInfo>('GET', `/servers/${enc(id)}/versions/${version}/naming`),
  operations: (id: string, version: string | number = 'latest') => json<OperationList>('GET', `/servers/${enc(id)}/versions/${version}/operations`),
  saveEdit: (id: string, base: number, changes: Change[], message?: string) =>
    json<Server>('POST', `/servers/${enc(id)}/edits`, { base, changes, message }),
  duplicate: (id: string, body: { id: string; title?: string }) => json<Server>('POST', `/servers/${enc(id)}/duplicate`, body),
  variant: (id: string, body: { id: string; title?: string; pin?: boolean }) => json<Server>('POST', `/servers/${enc(id)}/variants`, body),
  archiveServer: (id: string) => json<Server>('POST', `/servers/${enc(id)}/archive`),
  restoreServer: (id: string) => json<Server>('POST', `/servers/${enc(id)}/restore`),

  builds: (server: string, archived = false) => json<Items<Build>>('GET', `/servers/${enc(server)}/builds` + q({ archived: archived || undefined })),
  startBuild: (server: string, body: { version?: string | number; buildNumber?: number } = {}) =>
    json<Build>('POST', `/servers/${enc(server)}/builds`, body),
  build: (id: string) => json<Build>('GET', `/builds/${enc(id)}`),
  buildLog: (id: string) => text(`/builds/${enc(id)}/log`),
  artifacts: (id: string) => json<{ items: Artifact[]; packageFiles?: PackageFile[] }>('GET', `/builds/${enc(id)}/artifacts`),
  artifactUrl: (id: string, name: string, file?: string) => `/api/v1/builds/${enc(id)}/artifacts/${enc(name)}` + q({ file }),
  release: (id: string) => send('POST', `/builds/${enc(id)}/release`).then(async (r) => ({ status: r.status, body: await r.json() as Build | { approval: Approval } })),
  archiveBuild: (id: string) => json<Build>('POST', `/builds/${enc(id)}/archive`),
  restoreBuild: (id: string) => json<Build>('POST', `/builds/${enc(id)}/restore`),
  deleteBuild: (id: string) => json<Build>('DELETE', `/builds/${enc(id)}`),

  library: () => json<{ workspace: string; items: LibraryItem[] }>('GET', '/library'),
  libraryItem: (kind: string, name: string) => json<LibraryItem>('GET', `/library/${enc(kind)}/${enc(name)}`),
  libraryFile: (kind: string, name: string, version: number | string, path: string) =>
    text(`/library/${enc(kind)}/${enc(name)}/versions/${version}/files/${path.split('/').map(enc).join('/')}`),
  previewLibraryEdit: (kind: string, name: string, base: number, changes: Change[]) =>
    json<LibraryEditPreview>('POST', `/library/${enc(kind)}/${enc(name)}/edits`, { base, changes, dryRun: true }),
  saveLibraryEdit: (kind: string, name: string, base: number, changes: Change[], message?: string) =>
    json<LibraryItem>('POST', `/library/${enc(kind)}/${enc(name)}/edits`, { base, changes, message }),
  createLibraryItem: (kind: string, name: string, title: string, files: Record<string, string>) =>
    json<LibraryItem>('POST', `/library/${enc(kind)}` + q({ name, title }), { files }),
  applyNotices: (name: string, body: { servers?: string[]; version?: number; message?: string }) =>
    json<{ results: ApplyResult[] }>('POST', `/library/notices/${enc(name)}/apply`, body),

  /** What changed between two versions (`from`/`to`: a number, `latest` or `released`; defaults: the last release, the latest). */
  diff: (id: string, from?: string, to?: string) => json<VersionDiff>('GET', `/servers/${enc(id)}/diff` + q({ from, to })),
  identities: () => json<Items<Identity>>('GET', '/identities'),
  setPassword: (id: string, body: { password: string; currentPassword?: string; mustChange?: boolean }) =>
    json<{ identity: string; mustChange: boolean }>('PUT', `/identities/${enc(id)}/password`, body),
  unlock: (id: string) => json<{ identity: string; locked: boolean }>('POST', `/identities/${enc(id)}/unlock`),
  createIdentity: (body: { kind: string; name: string; role?: string; scopes?: string[] }) => json<Identity>('POST', '/identities', body),
  tokens: () => json<Items<Token>>('GET', '/tokens'),
  createToken: (body: { identity?: string; label?: string; scopes?: string[]; expiresInDays?: number }) => json<Token>('POST', '/tokens', body),
  revokeToken: (id: string) => json<Token>('DELETE', `/tokens/${enc(id)}`),
  approvals: (status = 'pending') => json<Items<Approval>>('GET', '/approvals' + q({ status })),
  decide: (id: string, approve: boolean, comment?: string) =>
    json<Approval>('POST', `/approvals/${enc(id)}/${approve ? 'approve' : 'reject'}`, { comment }),
  activity: (filter: { server?: string; actor?: string; action?: string; limit?: number } = {}) =>
    json<Items<Activity>>('GET', '/activity' + q(filter)),
}
