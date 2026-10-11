// Notices and per-tool overrides of titles and descriptions (definition-format.md §5–5.1), as the
// Overview and Tools pages write them: each is one item of a list in the root definition, set or
// removed as a whole by its id through the edits endpoint.

import type { Change, Notice, Rule, Selector, Tool } from './api'

export type Scope = 'all' | 'tag' | 'writes' | 'tools'

const WRITES = ['POST', 'PUT', 'PATCH', 'DELETE']

export const noticePath = (id: string) => `/descriptions/notices/[id=${id}]`
export const rulePath = (id: string) => `/overrides/rules/[id=${id}]`

/** A new notice's id, from its first words: `company-policy-data`. */
export function noticeId(text: string, taken: string[]): string {
  const base = text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').split('-').slice(0, 4).join('-') || 'notice'
  let id = base
  for (let n = 2; taken.includes(id); n++) id = `${base}-${n}`
  return id
}

/** The selector of a scope; none for every tool. */
export function scopeMatch(scope: Scope, tag: string, tools: string[]): Selector | undefined {
  switch (scope) {
    case 'all': return undefined
    case 'tag': return { tag: [tag] }
    case 'writes': return { method: WRITES }
    case 'tools': return { tool: tools }
  }
}

/** The scope a selector stands for, as the notice editor shows it. */
export function scopeOf(match: Selector | undefined): { scope: Scope; tag: string; tools: string[] } {
  if (!match) return { scope: 'all', tag: '', tools: [] }
  if (Array.isArray(match.tool)) return { scope: 'tools', tag: '', tools: match.tool as string[] }
  if (typeof match.tool === 'string') return { scope: 'tools', tag: '', tools: [match.tool] }
  if (match.tag && Object.keys(match).length === 1) return { scope: 'tag', tag: (Array.isArray(match.tag) ? match.tag[0] : match.tag) as string, tools: [] }
  if (Array.isArray(match.method) && WRITES.every((m) => (match.method as string[]).includes(m))) return { scope: 'writes', tag: '', tools: [] }
  return { scope: 'all', tag: '', tools: [] }
}

/** A selector in words, for lists. */
export function describeScope(match: Selector | undefined): string {
  if (!match) return 'every tool'
  const s = scopeOf(match)
  if (s.scope === 'tag') return `tools tagged ${s.tag}`
  if (s.scope === 'writes') return 'tools that change data'
  if (s.scope === 'tools') return s.tools.length === 1 ? s.tools[0] : `${s.tools.length} chosen tools`
  return Object.entries(match).map(([k, v]) => `${k} ${Array.isArray(v) ? v.join(' or ') : String(v)}`).join(', ')
}

export function setNotice(notice: Notice): Change {
  const value: Record<string, unknown> = { text: notice.text }
  if (notice.position === 'prepend') value.position = 'prepend'
  if (notice.match) value.match = notice.match
  return { op: 'set', path: noticePath(notice.id), value }
}

export const removeNotice = (id: string): Change => ({ op: 'remove', path: noticePath(id) })

/** The id of the page's own rule for a tool: by operationId (or method and path), or the tool name for other kinds. */
export function overrideId(tool: Tool): string {
  if (tool.kind === 'api') {
    if (tool.operation?.operationId) return `ui:${tool.operation.operationId}`
    const key = tool.operation?.key ?? tool.name
    return `ui:${key.replace(/^[^:]+:/, '').replace(/[^A-Za-z0-9:._-]+/g, '_')}`.slice(0, 128)
  }
  return `ui:${tool.name}`.slice(0, 128)
}

/** What the rule selects: the tool's operation, or (for other kinds) the tool by name. */
export function overrideMatch(tool: Tool): Selector {
  if (tool.kind !== 'api') return { tool: tool.name }
  if (tool.operation?.operationId) return { operationId: tool.operation.operationId }
  const variant = Object.values(tool.variants ?? {})[0]
  return { method: variant?.method, path: variant?.path }
}

export interface Override { title: string; description: string; params: Record<string, string> }

/**
 * The rule that sets a tool's title, description and parameter descriptions: what the page's earlier
 * rule for the tool set, plus what differs from now; null when there is nothing to set.
 */
export function setOverride(tool: Tool, now: Override, wanted: Override, existing?: Rule): Change | null {
  const rule: Rule = { match: overrideMatch(tool) }
  if (existing?.title) rule.title = existing.title
  if (existing?.description) rule.description = existing.description
  if (wanted.title !== now.title) rule.title = { set: wanted.title }
  if (wanted.description !== now.description) rule.description = { set: wanted.description }
  const params: Record<string, { description: { set: string } }> = {}
  for (const [name, p] of Object.entries(existing?.params ?? {})) {
    if (p.description?.set !== undefined) params[name] = { description: { set: p.description.set } }
  }
  for (const [name, value] of Object.entries(wanted.params)) {
    if (value !== (now.params[name] ?? '')) params[name] = { description: { set: value } }
  }
  if (tool.kind === 'api' && Object.keys(params).length > 0) rule.params = params
  if (!rule.title && !rule.description && !rule.params) return null
  return { op: 'set', path: rulePath(overrideId(tool)), value: rule }
}

const GROUPS = ['path', 'query', 'header', 'cookie']

/** A tool's parameter descriptions by `<group>.<name>` (the address rules use), from its input schema. */
export function paramDescriptions(tool: Tool): Record<string, string> {
  const schema = tool.inputSchema as { properties?: Record<string, { properties?: Record<string, { description?: string }> }> } | undefined
  const out: Record<string, string> = {}
  for (const group of GROUPS) {
    for (const [name, p] of Object.entries(schema?.properties?.[group]?.properties ?? {})) out[`${group}.${name}`] = p.description ?? ''
  }
  return out
}

/** The description without what notices add: the last step before the notices. */
export function ownDescription(tool: Tool): string {
  const steps = tool.provenance?.description ?? []
  const before = steps.filter((s) => s.step !== 'notice')
  if (before.length === steps.length) return tool.description ?? ''
  const last = before.at(-1)?.value
  return typeof last === 'string' ? last : ''
}
