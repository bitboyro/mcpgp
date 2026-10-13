import { useQuery } from '@tanstack/react-query'
import { api, type Manifest, type Tool } from './api'

export function useServer(id: string) {
  return useQuery({ queryKey: ['server', id], queryFn: () => api.server(id) })
}

export function useManifest(id: string, version: string | number = 'latest', enabled = true) {
  return useQuery({ queryKey: ['manifest', id, String(version)], queryFn: () => api.manifest(id, version), retry: false, enabled })
}

/**
 * The tools an agent sees in tools/list (tools-contract.md §15): every tool in plain mode; otherwise
 * the discovery tools, pack tools and instances_list, plus the pinned tools in hybrid mode. In
 * toolsets mode the tools of enabled toolsets come on top, at runtime.
 */
export function listedTools(manifest: Manifest): Tool[] {
  const mode = manifest.exposure.mode
  if (mode === 'plain') return manifest.tools
  const pinned = new Set(mode === 'hybrid' ? manifest.exposure.pinned ?? [] : [])
  return manifest.tools.filter((t) => t.kind !== 'api' || pinned.has(t.name))
}

export function apiTools(manifest: Manifest): Tool[] {
  return manifest.tools.filter((t) => t.kind === 'api')
}

export function counts(manifest: Manifest) {
  const levels = { error: 0, warning: 0, info: 0 }
  for (const d of manifest.diagnostics ?? []) levels[d.level]++
  return levels
}

export const EXPOSURE: Record<string, string> = {
  plain: 'Every tool listed',
  discovery: 'Search first',
  toolsets: 'Toolsets',
  hybrid: 'Pinned + search',
}

export const AUTH_LABELS: Record<string, string> = {
  none: 'None',
  basic: 'Basic',
  bearer: 'Bearer',
  apiKey: 'API key',
  oauth2ClientCredentials: 'OAuth client credentials',
  fromRequest: "The user's own, from the client",
  tokenExchange: "The signed-in user's identity (token exchange)",
  mtls: 'mTLS',
}
