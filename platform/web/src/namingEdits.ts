// Editing a server's naming on Tools & rules → Naming (naming.md §2): vocabulary entries and prefixes,
// written into the definition's `naming` and `apis[].toolPrefix` through the edits endpoint. A shared
// policy's entries are overridden or switched off (`null` removes an inherited key) in the server's own
// `naming`; the policy itself changes in the library.

import type { Change, Manifest } from './api'
import { nameRows } from './naming'

const escape = (key: string) => key.replace(/~/g, '~0').replace(/\//g, '~1')

export const vocabularyPath = (words: string) => `/naming/vocabulary/${escape(words)}`

/** "retrieve, fetch" → "retrieve|fetch": the key the policy uses; null when a word isn't a name word. */
export function wordsKey(input: string): string | null {
  const words = input.split(/[,|\s]+/).map((w) => w.trim().toLowerCase()).filter(Boolean)
  if (words.length === 0 || !words.every((w) => /^[a-z0-9_]+$/.test(w))) return null
  return words.join('|')
}

export const validWord = (value: string) => /^[a-z0-9_]+$/.test(value)
export const validPrefix = (value: string) => /^[a-z][a-z0-9_]{0,31}$/.test(value)

export function setWord(words: string, becomes: string): Change {
  return { op: 'set', path: vocabularyPath(words), value: becomes }
}

/** Switches off an entry the shared policy has, for this server only. */
export function switchOff(words: string): Change {
  return { op: 'set', path: vocabularyPath(words), value: null }
}

/** Removes the server's own entry (a policy entry it overrode comes back). */
export function removeWord(words: string): Change {
  return { op: 'remove', path: vocabularyPath(words) }
}

export const validVerb = (value: string) => /^[a-z][a-z0-9]{0,31}$/.test(value)

export function methodVerb(method: string, verb: string): Change {
  return { op: 'set', path: `/naming/methodVerbs/${method}`, value: verb }
}

export function apiPrefix(api: string, prefix: string): Change {
  return { op: 'set', path: `/apis/[id=${api}]/toolPrefix`, value: prefix }
}

export function serverPrefix(prefix: string): Change {
  return prefix ? { op: 'set', path: '/naming/namespace/server', value: prefix } : { op: 'remove', path: '/naming/namespace/server' }
}

export interface Renamed {
  /** The operation, as `METHOD /path`. */
  operation: string
  before: string
  after: string
  /** The lock file keeps the old name: only the policy's suggestion changes. */
  kept: boolean
}

/** Which tool names an edit changes, comparing the current manifest with the edited one. */
export function renames(before: Manifest, after: Manifest): Renamed[] {
  // tools of one operation (content-type variants) pair up by name length: a variant's name carries a suffix
  const key = (rows: ReturnType<typeof nameRows>) => {
    const seen = new Map<string, number>()
    return [...rows].sort((a, b) => a.id.localeCompare(b.id) || a.name.length - b.name.length || a.name.localeCompare(b.name)).map((r) => {
      const n = seen.get(r.id) ?? 0
      seen.set(r.id, n + 1)
      return [`${r.id}#${n}`, r] as const
    })
  }
  const old = new Map(key(nameRows(before)))
  const list: Renamed[] = []
  for (const [k, r] of key(nameRows(after))) {
    const was = old.get(k)
    if (!was) continue
    const operation = r.id.replace(/^[^:]+:/, '').replace(':', ' ').replace(/#.*$/, '')
    if (was.name !== r.name) list.push({ operation, before: was.name, after: r.name, kept: false })
    else if (was.computed !== r.computed) list.push({ operation, before: was.computed, after: r.computed, kept: true })
  }
  return list
}
