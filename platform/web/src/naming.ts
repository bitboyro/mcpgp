// The Naming section of Tools & rules (naming.md): the policy in words, and each tool's name next to
// what the pipeline would give it now and what the lock file keeps, read from the manifest's provenance.

import type { Manifest, NamingInfo, NamingPolicy, ProvenanceStep } from './api'

export interface NameRow {
  id: string
  name: string
  /** What the pipeline (with rules) gives the operation now, before the lock file. */
  computed: string
  /** How the name came about: `pipeline`, `rule`, `lock` (kept, same as computed), `drift` (kept, differs). */
  how: 'pipeline' | 'rule' | 'lock' | 'drift'
  since?: string
  rule?: string
}

const text = (v: unknown) => (typeof v === 'string' ? v : v === undefined ? '' : JSON.stringify(v))

export function nameRows(manifest: Manifest): NameRow[] {
  const rows: NameRow[] = []
  for (const tool of manifest.tools) {
    if (tool.kind !== 'api' || !tool.operation) continue
    const steps: ProvenanceStep[] = tool.provenance?.name ?? []
    const lock = steps.find((s) => s.step === 'lock')
    const beforeLock = steps.filter((s) => s.step !== 'lock')
    const computed = text(beforeLock.at(-1)?.value) || tool.name
    const rule = beforeLock.filter((s) => s.step === 'rule').at(-1)
    let how: NameRow['how'] = rule ? 'rule' : 'pipeline'
    if (lock) how = computed === tool.name ? 'lock' : 'drift'
    rows.push({ id: tool.operation.key, name: tool.name, computed, how, since: lock?.since, rule: rule?.file ? rule.file + (rule.pointer ? '#' + rule.pointer : '') : undefined })
  }
  const path = (id: string) => id.split(':').slice(2).join(':')
  return rows.sort((a, b) => path(a.id).localeCompare(path(b.id)) || a.id.localeCompare(b.id) || a.name.localeCompare(b.name))
}

/** Lock entries no tool of this build uses (the operation is gone or left out). */
export function unusedLockEntries(naming: NamingInfo, rows: NameRow[]) {
  const used = new Set(naming.tools ? naming.tools.map((t) => t.id) : rows.map((r) => r.id))
  return naming.lock.entries.filter((e) => !used.has(e.id))
}

export interface PolicyLine { label: string; value: string; own: boolean }

const SPLIT: Record<string, string> = { camel: 'camelCase', pascal: 'PascalCase', kebab: 'kebab-case', dot: 'dots', slash: 'slashes', snake: 'snake_case' }

/** The effective policy in words, each line marked when the server sets it itself rather than the policy. */
export function policyLines(naming: NamingInfo): PolicyLine[] {
  const e: NamingPolicy = naming.effective
  const own = naming.own
  const order = e.shape?.order ?? 'object_verb'
  const casing = e.shape?.case ?? 'snake'
  const example = order === 'verb_object' ? 'list_customer' : 'customer_list'
  const profile = e.limits?.profile ?? 'strict'
  return [
    { label: 'Names come from', value: `${e.source ?? 'operationId'}, else ${e.fallback === 'methodPath' || !e.fallback ? 'method and path' : e.fallback}`, own: own.source !== undefined || own.fallback !== undefined },
    { label: 'Words split at', value: (e.normalize?.split ?? ['camel', 'pascal', 'kebab', 'dot', 'slash']).map((s) => SPLIT[s] ?? s).join(', '), own: own.normalize?.split !== undefined },
    { label: 'Words dropped', value: (e.normalize?.dropWords ?? []).join(', ') || 'none', own: own.normalize?.dropWords !== undefined },
    { label: 'Shape', value: `${order === 'verb_object' ? 'verb, then noun' : 'noun, then verb'} (${casing === 'camel' ? example.replace(/_(\w)/g, (_, c: string) => c.toUpperCase()) : casing === 'kebab' ? example.replace('_', '-') : example}), ${e.shape?.number === 'plural' ? 'plural' : 'singular'} nouns`, own: own.shape !== undefined },
    { label: 'Verbs from methods', value: METHOD_VERBS.map(([m, fallback]) => `${m === 'LIST' ? 'GET list' : m} ${e.methodVerbs?.[m] ?? fallback}`).join(', '), own: own.methodVerbs !== undefined },
    { label: 'Prefixes', value: [e.namespace?.perApi === false ? 'no API prefix in single-API servers' : 'the API\'s toolPrefix', e.namespace?.server ? `then ${e.namespace.server}_` : ''].filter(Boolean).join(', '), own: own.namespace !== undefined },
    { label: 'Limits', value: profile === 'strict' ? 'at most 64 characters, a–z 0–9 _' : 'at most 128 characters, letters, digits, _ . -', own: own.limits?.profile !== undefined },
    { label: 'Too long', value: (e.limits?.onTooLong ?? 'shorten-then-hash') === 'error' ? 'an error' : 'shortened, then hashed', own: own.limits?.onTooLong !== undefined },
    { label: 'Same name twice', value: (e.collisions ?? 'error') === 'suffix-number' ? 'numbered (_2, _3)' : 'an error', own: own.collisions !== undefined },
  ]
}

export const METHOD_VERBS: [string, string, string][] = [
  ['LIST', 'list', 'GET on a collection'], ['GET', 'get', 'GET on one item'], ['POST', 'create', 'POST'],
  ['PUT', 'replace', 'PUT'], ['PATCH', 'update', 'PATCH'], ['DELETE', 'delete', 'DELETE'],
]

/** The verb names get from each HTTP method (when there's no operationId), and whether the server sets it. */
export function methodVerbs(naming: NamingInfo): { method: string; label: string; verb: string; own: boolean }[] {
  return METHOD_VERBS.map(([method, fallback, label]) => ({
    method, label, verb: naming.effective.methodVerbs?.[method] ?? fallback, own: naming.own.methodVerbs?.[method] !== undefined,
  }))
}

/** Vocabulary entries, with where each one comes from. */
export function vocabulary(naming: NamingInfo): { words: string; becomes: string; own: boolean }[] {
  return Object.entries(naming.effective.vocabulary ?? {}).map(([words, becomes]) => ({
    words: words.split('|').join(', '), becomes, own: naming.own.vocabulary?.[words] !== undefined,
  }))
}
