import type { Tool } from './api'
import { describeScope, noticeId, ownDescription, paramDescriptions, scopeMatch, scopeOf, setNotice, setOverride } from './descriptions'

const tool = {
  name: 'orders_account_list', kind: 'api', title: 'List accounts', description: 'Lists accounts.\n\nPolicy.',
  operation: { key: 'orders:GET:/accounts', operationId: 'listAccounts' },
  inputSchema: { type: 'object', properties: { query: { type: 'object', properties: { limit: { type: 'integer', description: 'How many' } } } } },
  provenance: { description: [{ step: 'spec', value: 'Lists accounts.' }, { step: 'notice', value: 'Policy.' }] },
} as unknown as Tool

describe('notices', () => {
  it('turns a scope into a selector and back', () => {
    expect(scopeMatch('all', '', [])).toBeUndefined()
    expect(scopeMatch('writes', '', [])).toEqual({ method: ['POST', 'PUT', 'PATCH', 'DELETE'] })
    expect(scopeOf({ tag: ['customers'] })).toEqual({ scope: 'tag', tag: 'customers', tools: [] })
    expect(scopeOf({ tool: ['a', 'b'] }).tools).toEqual(['a', 'b'])
    expect(describeScope({ method: ['POST', 'PUT', 'PATCH', 'DELETE'] })).toBe('tools that change data')
    expect(describeScope(undefined)).toBe('every tool')
  })

  it('writes one item of the notices list', () => {
    expect(noticeId('Company policy: data stays in the EU', ['company-policy-data-stays'])).toBe('company-policy-data-stays-2')
    expect(setNotice({ id: 'p', text: 'Audited.', position: 'prepend', match: { tag: ['x'] } }))
      .toEqual({ op: 'set', path: '/descriptions/notices/[id=p]', value: { text: 'Audited.', position: 'prepend', match: { tag: ['x'] } } })
  })
})

describe('per-tool overrides', () => {
  it('reads what the tool has before notices', () => {
    expect(ownDescription(tool)).toBe('Lists accounts.')
    expect(paramDescriptions(tool)).toEqual({ 'query.limit': 'How many' })
  })

  it('writes a rule with only what changed', () => {
    const now = { title: 'List accounts', description: 'Lists accounts.', params: { 'query.limit': 'How many' } }
    expect(setOverride(tool, now, now)).toBeNull()
    expect(setOverride(tool, now, { ...now, description: 'Every account.', params: { 'query.limit': 'At most 100' } })).toEqual({
      op: 'set', path: '/overrides/rules/[id=ui:listAccounts]',
      value: { match: { operationId: 'listAccounts' }, description: { set: 'Every account.' }, params: { 'query.limit': { description: { set: 'At most 100' } } } },
    })
    // an earlier override keeps what it set
    expect(setOverride(tool, now, { ...now, title: 'Accounts' }, { id: 'ui:listAccounts', description: { set: 'Every account.' } })?.value)
      .toEqual({ match: { operationId: 'listAccounts' }, title: { set: 'Accounts' }, description: { set: 'Every account.' } })
    const pack = { ...tool, name: 'orders_audit_search', kind: 'pack', operation: undefined } as unknown as Tool
    expect(setOverride(pack, now, { ...now, title: 'Audit' })?.value).toEqual({ match: { tool: 'orders_audit_search' }, title: { set: 'Audit' } })
  })
})
