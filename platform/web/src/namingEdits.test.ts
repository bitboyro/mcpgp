import type { Manifest } from './api'
import { apiPrefix, removeWord, renames, serverPrefix, setWord, switchOff, validPrefix, wordsKey } from './namingEdits'

const manifest = (names: [string, string, boolean][]) => ({
  tools: names.map(([key, name, locked]) => ({
    name, kind: 'api', operation: { key },
    provenance: { name: [{ step: 'namespace', value: name.replace('fetch', 'get') }, ...(locked ? [{ step: 'lock', value: name }] : [])] },
  })),
}) as unknown as Manifest

describe('editing naming', () => {
  it('writes vocabulary entries into the server\'s own naming', () => {
    expect(wordsKey('Retrieve, fetch')).toBe('retrieve|fetch')
    expect(wordsKey('no-dash')).toBeNull()
    expect(setWord('retrieve|fetch', 'get')).toEqual({ op: 'set', path: '/naming/vocabulary/retrieve|fetch', value: 'get' })
    expect(switchOff('add|new')).toEqual({ op: 'set', path: '/naming/vocabulary/add|new', value: null })
    expect(removeWord('a/b')).toEqual({ op: 'remove', path: '/naming/vocabulary/a~1b' })
  })

  it('writes prefixes', () => {
    expect(apiPrefix('orders', 'ord')).toEqual({ op: 'set', path: '/apis/[id=orders]/toolPrefix', value: 'ord' })
    expect(serverPrefix('')).toEqual({ op: 'remove', path: '/naming/namespace/server' })
    expect(validPrefix('ord_2')).toBe(true)
    expect(validPrefix('2ord')).toBe(false)
  })

  it('pairs the tools of one operation by their variant', () => {
    const tool = (name: string) => ({ name, kind: 'api', operation: { key: 'o:POST:/c' }, provenance: { name: [{ step: 'namespace', value: name }] } })
    const before = { tools: [tool('orders_c_create'), tool('orders_c_create_multipart')] } as unknown as Manifest
    const after = { tools: [tool('acme_orders_c_create_multipart'), tool('acme_orders_c_create')] } as unknown as Manifest
    expect(renames(before, after).map((r) => [r.before, r.after])).toEqual([
      ['orders_c_create', 'acme_orders_c_create'], ['orders_c_create_multipart', 'acme_orders_c_create_multipart']])
  })

  it('tells renames from names the lock file keeps', () => {
    const before = manifest([['o:GET:/a', 'orders_a_list', false], ['o:GET:/b', 'orders_b_get', true]])
    const after = {
      tools: [
        { name: 'ord_a_list', kind: 'api', operation: { key: 'o:GET:/a' }, provenance: { name: [{ step: 'namespace', value: 'ord_a_list' }] } },
        { name: 'orders_b_get', kind: 'api', operation: { key: 'o:GET:/b' }, provenance: { name: [{ step: 'namespace', value: 'ord_b_get' }, { step: 'lock', value: 'orders_b_get' }] } },
      ],
    } as unknown as Manifest
    expect(renames(before, after)).toEqual([
      { operation: 'GET /a', before: 'orders_a_list', after: 'ord_a_list', kept: false },
      { operation: 'GET /b', before: 'orders_b_get', after: 'ord_b_get', kept: true },
    ])
  })
})
