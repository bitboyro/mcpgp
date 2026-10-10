// A fake platform API for component tests: routes "METHOD /path" to JSON answers and records calls.

export interface Call { method: string; path: string; headers: Record<string, string>; body: unknown }

type Answer = unknown | ((call: Call) => unknown)

export function fakeApi(routes: Record<string, Answer>) {
  const calls: Call[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost')
    const method = init.method ?? 'GET'
    const path = url.pathname.replace(/^\/api\/v1/, '') + url.search
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body
    const call = { method, path, headers: (init.headers ?? {}) as Record<string, string>, body }
    calls.push(call)
    const key = Object.keys(routes).find((k) => k === `${method} ${path}`) ?? Object.keys(routes).find((k) => k === `${method} ${url.pathname.replace(/^\/api\/v1/, '')}`)
    if (!key) return new Response(JSON.stringify({ code: 'NOT_FOUND', detail: `no fake for ${method} ${path}` }), { status: 404 })
    const answer = routes[key]
    const value = typeof answer === 'function' ? (answer as (c: Call) => unknown)(call) : answer
    if (value instanceof Response) return value
    return new Response(JSON.stringify(value), { status: method === 'POST' ? 201 : 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fetchMock)
  return { calls }
}

export const ME = {
  id: 'id_1', kind: 'person', name: 'ana', created: '2026-10-01T00:00:00Z', disabled: false,
  scopes: ['admin'], tokenScopes: ['admin'],
}

export const SETTINGS = {
  workspace: 'acme', auth: { local: true, oidc: false }, signing: { configured: true },
  retention: { archiveAfterDays: 90, deleteArchivedAfterDays: 365, keepLast: 10 },
  guardrails: { agentBuildsPerHour: 20, agentVersionsPerHour: 60 },
}
