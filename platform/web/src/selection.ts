// Turning operations on and off on Tools & rules: each switch becomes a change to the API's `select`
// block (definition-format.md §4), written through the edits endpoint like any other page change.
// Turning one tool off adds one exclude entry; turning an operation on undoes what left it out, which
// may be broader than that one operation (the page says so before it happens).

import type { Change, Operation, Select, Selector } from './api'

export function asList(value: Selector | Selector[] | undefined): Selector[] {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value : [value]
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map((k) => JSON.stringify(k) + ':' + canonical((value as Record<string, unknown>)[k])).join(',') + '}'
  }
  return JSON.stringify(value)
}

export function same(a: unknown, b: unknown): boolean {
  return canonical(a) === canonical(b)
}

/** The selector for exactly this operation: its operationId, else method and path. */
export function entryFor(op: Pick<Operation, 'operationId' | 'method' | 'path'>): Selector {
  return op.operationId ? { operationId: op.operationId } : { method: op.method, path: op.path }
}

/** A selector in a few words: `tag users-roles or system`, `path /internal/**`. */
export function describeSelector(selector: Selector | undefined): string {
  if (!selector) return ''
  return Object.entries(selector).map(([k, v]) => {
    const values = Array.isArray(v) ? v.map(String).join(' or ') : typeof v === 'object' ? JSON.stringify(v) : String(v)
    return `${k} ${values}`
  }).join(', ')
}

/** Why an operation is off, in the words of the page. */
export function whyOff(op: Operation): string {
  if (op.state === 'hidden') return 'hidden by a rule'
  const e = op.exclusion
  if (!e) return ''
  switch (e.kind) {
    case 'access': return e.reason === 'access: read-only' ? 'read only: no writes' : 'read and write: no deletes'
    case 'include': return 'not in select.include'
    case 'exclude': return `excluded by ${describeSelector(e.selector)}`
    case 'deprecated': return 'deprecated (hidden)'
  }
}

export function turnOff(select: Select, op: Operation): Select {
  return { ...select, exclude: [...asList(select.exclude), entryFor(op)] }
}

export interface TurnOn {
  select: Select
  /** Set when the change reaches further than this operation: what it does, in words. */
  broad?: string
}

/** Undoes what leaves the operation out; null for operations a rule hides (those change in the rule's file). */
export function turnOn(select: Select, op: Operation): TurnOn | null {
  const e = op.exclusion
  if (op.state !== 'excluded' || !e) return null
  switch (e.kind) {
    case 'exclude': {
      const list = asList(select.exclude)
      const index = e.index ?? list.findIndex((s) => same(s, e.selector))
      if (index < 0 || index >= list.length) return null
      const rest = list.filter((_, i) => i !== index)
      const next = { ...select, exclude: rest }
      return same(list[index], entryFor(op)) ? { select: next } : { select: next, broad: `removes the exclude entry “${describeSelector(list[index])}”` }
    }
    case 'include':
      return { select: { ...select, include: [...asList(select.include), entryFor(op)] } }
    case 'access': {
      const access = op.method === 'DELETE' ? 'all' : 'read-write'
      return { select: { ...select, access }, broad: access === 'all' ? 'sets access to everything (deletes included)' : 'sets access to read and write' }
    }
    case 'deprecated':
      return { select: { ...select, deprecated: 'mark' }, broad: 'shows every deprecated operation, marked as deprecated' }
  }
}

/** Turns off several tools: one `tag` entry when they are exactly a tag's operations, else one entry each. */
export function turnOffAll(select: Select, ops: Operation[], tag?: string): Select {
  if (tag) return { ...select, exclude: [...asList(select.exclude), { tag: [tag] }] }
  return ops.reduce(turnOff, select)
}

/** The edits that turn the definition's `select` into the working one. */
export function changes(api: string, original: Select, working: Select): Change[] {
  const list: Change[] = []
  const path = (field: string) => `/apis/[id=${api}]/select/${field}`
  for (const field of ['exclude', 'include'] as const) {
    const before = asList(original[field])
    const after = asList(working[field])
    if (same(before, after)) continue
    // an empty include would leave everything out: no include means everything
    list.push(after.length === 0 ? { op: 'remove', path: path(field) } : { op: 'set', path: path(field), value: after })
  }
  for (const field of ['access', 'deprecated'] as const) {
    if ((original[field] ?? null) !== (working[field] ?? null)) list.push({ op: 'set', path: path(field), value: working[field] })
  }
  return list
}
