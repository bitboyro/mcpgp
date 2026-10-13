// Uploading an API's spec on the platform (spec/platform-api.md §2.1): the files the person picks, added
// to as the platform asks for missing ones, and what to show for a SPEC_PROBLEMS answer.

import { ApiError, type NameClash, type SpecProblems } from './api'

export const SPEC_FILE = /\.(json|ya?ml)$/i

/** Files picked one by one, a folder, or a zip; picking more adds to what's there (a zip replaces everything). */
export function mergeFiles(current: Record<string, string> | Blob | null, added: Record<string, string> | Blob): Record<string, string> | Blob {
  if (added instanceof Blob || current === null || current instanceof Blob) return added
  return { ...current, ...added }
}

/** Only the files that can be part of a spec. */
export function specOnly(files: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(files).filter(([path]) => SPEC_FILE.test(path)))
}

/** The SPEC_PROBLEMS of an error, or null for other errors. */
export function nameClashes(error: unknown): NameClash[] | null {
  if (!(error instanceof ApiError) || error.code !== 'NAME_COLLISIONS') return null
  return (error.body as { collisions?: NameClash[] }).collisions ?? []
}

export function specProblems(error: unknown): SpecProblems | null {
  if (!(error instanceof ApiError) || error.code !== 'SPEC_PROBLEMS') return null
  return error.body as SpecProblems
}

/** `paths/customers.json` → the name a person recognizes, with its folder. */
export const fileLabel = (path: string) => path

/** A server id from a title, as the platform suggests it: "Orders API" → `orders-api`. */
export const idFrom = (text: string) => text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 63)
