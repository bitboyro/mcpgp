// The settings people change on a server's page, in plain words: what each option writes into the
// definition (definition-format.md), and how its current value is read from the manifest, which
// already includes everything a variant inherits.

import type { Change, Manifest } from './api'

export interface Option { value: string; label: string; hint?: string }

export interface Choice {
  id: string
  title: string
  hint: string
  options: Option[]
  current: (m: Manifest) => string
  /** The definition change for an option. */
  change: (value: string) => Change
}

const TRANSPORTS: Record<string, { allowed: string[]; default: string }> = {
  stdio: { allowed: ['stdio'], default: 'stdio' },
  both: { allowed: ['stdio', 'http'], default: 'stdio' },
  http: { allowed: ['http'], default: 'http' },
}

/** The API's `select.access`, as the compiler reports it when it leaves operations out. */
export function access(m: Manifest, api: string): string {
  for (const d of m.diagnostics ?? []) {
    const reason = (d as { reason?: string }).reason ?? ''
    const operation = (d as { operation?: string }).operation ?? ''
    const match = /^access: (read-only|read-write)$/.exec(reason)
    if (d.code === 'OPERATION_EXCLUDED' && match && operation.startsWith(api + ':')) return match[1]
  }
  const tools = m.tools.filter((t) => t.kind === 'api' && t.api === api)
  if (tools.some((t) => Object.values(t.variants ?? {}).some((v) => v.method === 'DELETE'))) return 'all'
  if (tools.length > 0 && tools.every((t) => t.annotations?.readOnlyHint)) return 'read-only'
  return 'read-write'
}

export function choices(m: Manifest): Choice[] {
  const list: Choice[] = [
    {
      id: 'exposure', title: 'What the agent sees', hint: 'How tools reach the agent; large APIs overflow its context when all are listed.',
      options: [
        { value: 'plain', label: 'Every tool' },
        { value: 'hybrid', label: 'Pinned + search' },
        { value: 'discovery', label: 'Search first' },
        { value: 'toolsets', label: 'Toolsets' },
      ],
      current: (x) => x.exposure.mode,
      change: (v) => ({ op: 'set', path: '/exposure/mode', value: v }),
    },
  ]
  for (const api of m.apis) {
    list.push({
      id: 'access:' + api.id,
      title: m.apis.length > 1 ? `What it may do in ${api.title ?? api.id}` : 'What it may do',
      hint: 'Which operations become tools.',
      options: [
        { value: 'read-only', label: 'Read only' },
        { value: 'read-write', label: 'Read and write', hint: 'no deletes' },
        { value: 'all', label: 'Everything' },
      ],
      current: (x) => access(x, api.id),
      change: (v) => ({ op: 'set', path: `/apis/[id=${api.id}]/select/access`, value: v }),
    })
  }
  list.push(
    {
      id: 'instances', title: 'Target servers', hint: 'One server, or several that agents choose per call.',
      options: [{ value: 'single', label: 'One server' }, { value: 'multi', label: 'Several, per call' }],
      current: (x) => x.instances.mode,
      change: (v) => ({ op: 'set', path: '/instances/mode', value: v }),
    },
  )
  if (m.instances.mode === 'multi') {
    list.push({
      id: 'mixed', title: 'When targets run different versions', hint: 'How one tool works across API versions.',
      options: [
        { value: 'union', label: 'One tool, checked per call' },
        { value: 'namespaced', label: 'Separate tools per server' },
        { value: 'forbid', label: 'Same version only' },
      ],
      current: (x) => x.instances.mixedContracts ?? 'union',
      change: (v) => ({ op: 'set', path: '/instances/mixedContracts', value: v }),
    })
  }
  list.push(
    {
      id: 'transports', title: 'How agents connect', hint: 'stdio for desktop clients; HTTP for shared servers.',
      options: [{ value: 'stdio', label: 'stdio' }, { value: 'both', label: 'stdio and HTTP' }, { value: 'http', label: 'HTTP' }],
      current: (x) => {
        const allowed = [...(x.runtime.transports?.allowed ?? ['stdio'])].sort().join(',')
        return allowed === 'http' ? 'http' : allowed === 'http,stdio' ? 'both' : 'stdio'
      },
      change: (v) => ({ op: 'set', path: '/runtimeDefaults/transports', value: TRANSPORTS[v] }),
    },
    {
      id: 'safety', title: 'Destructive calls', hint: 'Whether the agent must confirm before deleting or changing data.',
      options: [{ value: 'confirm', label: 'Ask to confirm' }, { value: 'direct', label: 'Run directly' }],
      current: (x) => x.policies.confirmDestructive === false ? 'direct' : 'confirm',
      change: (v) => ({ op: 'set', path: '/safety/confirmDestructive', value: v === 'confirm' }),
    },
    {
      id: 'audit-days', title: 'How long the audit log is kept', hint: 'The default the package ships with; whoever runs it can still change it.',
      options: [{ value: '30', label: '30 days' }, { value: '90', label: '90 days' }, { value: '180', label: '180 days' }, { value: '365', label: 'A year' }],
      current: (x) => String(x.policies.runtimeDefaults?.audit?.file?.retainDays ?? 30),
      change: (v) => ({ op: 'set', path: '/runtimeDefaults/audit/file/retainDays', value: Number(v) }),
    },
    {
      id: 'audit-arguments', title: 'Tool arguments in the audit log', hint: 'Credentials and secret fields are always masked.',
      options: [
        { value: 'redacted', label: 'Names, values masked' },
        { value: 'hashed', label: 'A fingerprint' },
        { value: 'none', label: 'Not recorded' },
      ],
      current: (x) => x.policies.runtimeDefaults?.audit?.arguments ?? 'redacted',
      change: (v) => ({ op: 'set', path: '/runtimeDefaults/audit/arguments', value: v }),
    },
  )
  return list
}

export const PACKS: [string, string][] = [
  ['audit', 'Audit log'],
  ['introspection', 'Explain tools'],
  ['diagnostics', 'Connectivity checks'],
  ['health', 'Health'],
  ['schema', 'Schemas'],
  ['recording', 'Recording'],
]

export function packOn(m: Manifest, id: string): boolean {
  return m.packs?.some((p) => p.id === id) ?? false
}

/** In a variant, null removes what the original has; elsewhere the key goes. */
export function packChange(id: string, on: boolean, variant: boolean): Change {
  if (on) return { op: 'set', path: `/packs/${id}`, value: {} }
  return variant ? { op: 'set', path: `/packs/${id}`, value: null } : { op: 'remove', path: `/packs/${id}` }
}

/** Lines of two texts, with what was removed and added; unchanged lines far from a change are left out. */
export function diffLines(before: string, after: string, context = 2): { kind: ' ' | '-' | '+' | '…'; text: string }[] {
  const a = before.split('\n')
  const b = after.split('\n')
  const table = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) table[i][j] = a[i] === b[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1])
  }
  const all: { kind: ' ' | '-' | '+'; text: string }[] = []
  let i = 0
  let j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) { all.push({ kind: ' ', text: a[i] }); i++; j++ }
    else if (i < a.length && (j === b.length || table[i + 1][j] >= table[i][j + 1])) { all.push({ kind: '-', text: a[i] }); i++ }
    else { all.push({ kind: '+', text: b[j] }); j++ }
  }
  const near = all.map((_, k) => all.slice(Math.max(0, k - context), k + context + 1).some((l) => l.kind !== ' '))
  const out: { kind: ' ' | '-' | '+' | '…'; text: string }[] = []
  all.forEach((line, k) => {
    if (near[k]) out.push(line)
    else if (out.length === 0 || out[out.length - 1].kind !== '…') out.push({ kind: '…', text: '' })
  })
  while (out.length > 0 && out[out.length - 1].kind === '…') out.pop()
  return out
}
