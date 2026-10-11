import type { Manifest, NamingInfo } from './api'
import { nameRows, policyLines, unusedLockEntries, vocabulary } from './naming'

const tool = (name: string, key: string, steps: object[]) => ({ name, kind: 'api', operation: { key }, provenance: { name: steps } })
const manifest = {
  tools: [
    tool('orders_customer_list', 'orders:GET:/customers', [{ step: 'shape', value: 'customer_list' }, { step: 'namespace', value: 'orders_customer_list' },
      { step: 'lock', value: 'orders_customer_list', file: 'mcp-names.lock', since: '1.0.0' }]),
    tool('orders_customer_get', 'orders:GET:/customers/{id}', [{ step: 'namespace', value: 'orders_customer_account_get' },
      { step: 'lock', value: 'orders_customer_get', since: '1.0.0' }]),
    tool('orders_refund', 'orders:POST:/orders/{id}/refund', [{ step: 'namespace', value: 'orders_order_refund' },
      { step: 'rule', value: 'orders_refund', file: 'overrides/20-orders.yaml', pointer: '/rules/0' }]),
    tool('orders_account_list', 'orders:GET:/accounts', [{ step: 'namespace', value: 'orders_account_list' }]),
    { name: 'search_tools', kind: 'builtin' },
  ],
} as unknown as Manifest

const naming: NamingInfo = {
  extends: 'mcpgp://acme/naming/acme-standard@2',
  own: { vocabulary: { customer_account: 'customer' } },
  effective: { source: 'operationId', fallback: 'methodPath', vocabulary: { 'retrieve|fetch': 'get', customer_account: 'customer' }, limits: { profile: 'strict' } },
  lock: { present: true, entries: [{ id: 'orders:GET:/customers', name: 'orders_customer_list' }, { id: 'orders:GET:/users', name: 'orders_user_list', since: '1.0.0' }] },
}

describe('naming', () => {
  it('says how each tool got its name', () => {
    const rows = nameRows(manifest)
    expect(rows.map((r) => [r.name, r.how, r.computed])).toEqual([
      ['orders_account_list', 'pipeline', 'orders_account_list'],
      ['orders_customer_list', 'lock', 'orders_customer_list'],
      ['orders_customer_get', 'drift', 'orders_customer_account_get'],
      ['orders_refund', 'rule', 'orders_refund'],
    ])
    expect(rows[1].since).toBe('1.0.0')
    expect(rows[3].rule).toBe('overrides/20-orders.yaml#/rules/0')
    expect(unusedLockEntries(naming, rows).map((e) => e.id)).toEqual(['orders:GET:/users'])
  })

  it('puts the policy in words and marks what the server sets itself', () => {
    const lines = policyLines(naming)
    expect(lines[0]).toEqual({ label: 'Names come from', value: 'operationId, else method and path', own: false })
    expect(lines.find((l) => l.label === 'Shape')!.value).toBe('noun, then verb (customer_list), singular nouns')
    expect(vocabulary(naming)).toEqual([{ words: 'retrieve, fetch', becomes: 'get', own: false }, { words: 'customer_account', becomes: 'customer', own: true }])
  })
})
