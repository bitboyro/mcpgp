// Shared notices (definition-format.md §5.1): library items of kind `notices` that a server includes with
// `descriptions.include`. Helpers for the Library page and the server's Overview, which edit the include
// list and the sets themselves through the edits endpoints.

import type { Change, Notice } from './api'

export const INCLUDE_PATH = '/descriptions/include'

/** `mcpgp://acme/notices/company-policy@2` → its name and version (null: the latest); null for other references. */
export function parseNoticeRef(ref: string): { name: string; version: string | null } | null {
  const m = /^mcpgp:\/\/[a-z0-9][a-z0-9-]*\/notices\/([A-Za-z0-9._-]+)(?:@([A-Za-z0-9._-]+))?$/.exec(ref)
  if (!m) return null
  return { name: m[1], version: m[2] === undefined || m[2] === 'latest' ? null : m[2] }
}

export const noticeRef = (workspace: string, name: string, version: number) => `mcpgp://${workspace}/notices/${name}@${version}`

/** The include list as written: one reference or several. */
export function includeList(value: string | string[] | undefined): string[] {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value : [value]
}

/** Adds a set, or moves the one already there (any version) to this reference. */
export function withSet(list: string[], ref: string): string[] {
  const name = parseNoticeRef(ref)?.name
  const at = list.findIndex((r) => parseNoticeRef(r)?.name === name)
  if (at < 0) return [...list, ref]
  return list.map((r, i) => (i === at ? ref : r))
}

export const withoutSet = (list: string[], name: string) => list.filter((r) => parseNoticeRef(r)?.name !== name)

/** The edit that turns the include list into `next` (none when it doesn't change, a removal when it becomes empty). */
export function includeChange(before: string[], next: string[]): Change | null {
  if (before.length === next.length && before.every((r, i) => r === next[i])) return null
  return next.length === 0 ? { op: 'remove', path: INCLUDE_PATH } : { op: 'set', path: INCLUDE_PATH, value: next }
}

/** A notice in a set's file: `/notices/[id=…]`, set or removed as a whole. */
export const setNoticePath = (id: string) => `/notices/[id=${id}]`

export function setSetNotice(notice: Notice): Change {
  const value: Record<string, unknown> = { text: notice.text }
  if (notice.position === 'prepend') value.position = 'prepend'
  if (notice.match) value.match = notice.match
  return { op: 'set', path: setNoticePath(notice.id), value }
}
