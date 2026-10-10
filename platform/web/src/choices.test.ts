import type { Manifest } from './api'
import { access, choices, diffLines, packChange } from './choices'

const manifest = (over: Partial<Manifest> = {}) => ({
  exposure: { mode: 'hybrid', pinned: [] },
  instances: { mode: 'multi', mixedContracts: 'union' },
  runtime: { transports: { allowed: ['http', 'stdio'], default: 'stdio' } },
  policies: { confirmDestructive: true },
  packs: [],
  apis: [{ id: 'orders', contracts: [] }],
  tools: [],
  diagnostics: [{ level: 'info', code: 'OPERATION_EXCLUDED', message: '', operation: 'orders:DELETE:/customers/{id}', reason: 'access: read-write' }],
  ...over,
}) as unknown as Manifest

describe('settings on the page', () => {
  it('reads the current values from the manifest', () => {
    const m = manifest()
    const now = Object.fromEntries(choices(m).map((c) => [c.id, c.current(m)]))
    expect(now).toEqual({ exposure: 'hybrid', 'access:orders': 'read-write', instances: 'multi', mixed: 'union', transports: 'both', safety: 'confirm' })
  })

  it('writes each option into the definition', () => {
    const byId = Object.fromEntries(choices(manifest()).map((c) => [c.id, c]))
    expect(byId['access:orders'].change('read-only')).toEqual({ op: 'set', path: '/apis/[id=orders]/select/access', value: 'read-only' })
    expect(byId.transports.change('http')).toEqual({ op: 'set', path: '/runtimeDefaults/transports', value: { allowed: ['http'], default: 'http' } })
    expect(byId.safety.change('direct')).toEqual({ op: 'set', path: '/safety/confirmDestructive', value: false })
  })

  it('only asks about mixed versions with several target servers', () => {
    expect(choices(manifest({ instances: { mode: 'single' } })).map((c) => c.id)).not.toContain('mixed')
  })

  it('tells read-only apart from everything without exclusions', () => {
    const readOnly = manifest({ diagnostics: [], tools: [{ name: 'a', kind: 'api', api: 'orders', annotations: { readOnlyHint: true } }] })
    expect(access(readOnly, 'orders')).toBe('read-only')
    const all = manifest({ diagnostics: [], tools: [{ name: 'd', kind: 'api', api: 'orders', variants: { '2.0': { method: 'DELETE', path: '/x' } } }] })
    expect(access(all, 'orders')).toBe('all')
  })

  it('turns a pack off in a variant with null, so the original keeps it', () => {
    expect(packChange('audit', false, true)).toEqual({ op: 'set', path: '/packs/audit', value: null })
    expect(packChange('audit', false, false)).toEqual({ op: 'remove', path: '/packs/audit' })
    expect(packChange('audit', true, false)).toEqual({ op: 'set', path: '/packs/audit', value: {} })
  })
})

describe('the review', () => {
  it('shows removed then added lines, with a little context', () => {
    const before = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'].join('\n')
    const after = ['a', 'b', 'c', 'D', 'e', 'f', 'g', 'h'].join('\n')
    expect(diffLines(before, after, 1)).toEqual([
      { kind: '…', text: '' }, { kind: ' ', text: 'c' }, { kind: '-', text: 'd' }, { kind: '+', text: 'D' }, { kind: ' ', text: 'e' },
    ])
  })
})
