import { includeChange, includeList, noticeRef, parseNoticeRef, setSetNotice, withSet, withoutSet } from './sharedNotices'

describe('shared notices', () => {
  it('reads references to notice sets', () => {
    expect(parseNoticeRef('mcpgp://acme/notices/company-policy@2')).toEqual({ name: 'company-policy', version: '2' })
    expect(parseNoticeRef('mcpgp://acme/notices/company-policy')).toEqual({ name: 'company-policy', version: null })
    expect(parseNoticeRef('mcpgp://acme/naming/acme-standard@2')).toBeNull()
    expect(noticeRef('acme', 'policy', 3)).toBe('mcpgp://acme/notices/policy@3')
  })

  it('adds a set once, and moves it to another version in place', () => {
    const list = includeList('mcpgp://acme/notices/a@1')
    expect(withSet(list, 'mcpgp://acme/notices/b@1')).toEqual(['mcpgp://acme/notices/a@1', 'mcpgp://acme/notices/b@1'])
    expect(withSet(['mcpgp://acme/notices/a@1', 'policy/local.yaml'], 'mcpgp://acme/notices/a@2')).toEqual(['mcpgp://acme/notices/a@2', 'policy/local.yaml'])
    expect(withoutSet(['mcpgp://acme/notices/a@1', 'mcpgp://acme/notices/b@1'], 'a')).toEqual(['mcpgp://acme/notices/b@1'])
  })

  it('writes the include list, or removes it when it becomes empty', () => {
    expect(includeChange([], ['mcpgp://acme/notices/a@1'])).toEqual({ op: 'set', path: '/descriptions/include', value: ['mcpgp://acme/notices/a@1'] })
    expect(includeChange(['mcpgp://acme/notices/a@1'], [])).toEqual({ op: 'remove', path: '/descriptions/include' })
    expect(includeChange(['x'], ['x'])).toBeNull()
    expect(setSetNotice({ id: 'eu', text: 'Data stays in the EU.', position: 'append' })).toEqual({ op: 'set', path: '/notices/[id=eu]', value: { text: 'Data stays in the EU.' } })
  })
})
