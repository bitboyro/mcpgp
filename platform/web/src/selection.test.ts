import type { Operation, Select } from './api'
import { changes, describeSelector, entryFor, turnOff, turnOffAll, turnOn, whyOff } from './selection'

const op = (over: Partial<Operation>): Operation => ({
  key: 'orders:GET:/accounts', method: 'GET', path: '/accounts', operationId: 'listAccounts', deprecated: false, tags: ['accounts'],
  contracts: ['2.0'], state: 'tool', tool: 'orders_account_list', ...over,
})

const select: Select = { access: 'read-write', exclude: [{ tag: ['users-roles', 'system'] }, { path: '/internal/**' }], deprecated: 'hide' }

describe('turning operations on and off', () => {
  it('turns a tool off with one exclude entry for exactly that operation', () => {
    const next = turnOff(select, op({}))
    expect(next.exclude).toEqual([{ tag: ['users-roles', 'system'] }, { path: '/internal/**' }, { operationId: 'listAccounts' }])
    expect(changes('orders', select, next)).toEqual([{ op: 'set', path: '/apis/[id=orders]/select/exclude', value: next.exclude }])
    expect(entryFor({ method: 'GET', path: '/health' })).toEqual({ method: 'GET', path: '/health' })
  })

  it('turns it back on by removing that entry, which leaves nothing to save', () => {
    const off = turnOff(select, op({}))
    const on = turnOn(off, op({ state: 'excluded', exclusion: { kind: 'exclude', reason: 'excluded by select.exclude', index: 2, selector: { operationId: 'listAccounts' } } }))
    expect(on?.broad).toBeUndefined()
    expect(changes('orders', select, on!.select)).toEqual([])
  })

  it('says when turning one on reaches further', () => {
    const users = op({ operationId: 'listUsers', state: 'excluded', exclusion: { kind: 'exclude', reason: '', index: 0, selector: { tag: ['users-roles', 'system'] } } })
    const on = turnOn(select, users)
    expect(on?.broad).toBe('removes the exclude entry “tag users-roles or system”')
    expect(on?.select.exclude).toEqual([{ path: '/internal/**' }])

    const del = turnOn(select, op({ method: 'DELETE', state: 'excluded', exclusion: { kind: 'access', reason: 'access: read-write' } }))
    expect(del?.select.access).toBe('all')
    expect(del?.broad).toContain('deletes included')
    expect(turnOn(select, op({ deprecated: true, state: 'excluded', exclusion: { kind: 'deprecated', reason: 'deprecated' } }))?.select.deprecated).toBe('mark')
  })

  it('adds operations to include, and never leaves an empty include behind', () => {
    const only: Select = { include: [{ tag: ['orders'] }] }
    const on = turnOn(only, op({ state: 'excluded', exclusion: { kind: 'include', reason: 'not included' } }))
    expect(on?.select.include).toEqual([{ tag: ['orders'] }, { operationId: 'listAccounts' }])
    expect(changes('orders', only, { include: [] })).toEqual([{ op: 'remove', path: '/apis/[id=orders]/select/include' }])
  })

  it('leaves operations a rule hides alone', () => {
    expect(turnOn(select, op({ state: 'hidden' }))).toBeNull()
    expect(whyOff(op({ state: 'hidden' }))).toBe('hidden by a rule')
  })

  it('turns off a whole tag with one entry', () => {
    expect(turnOffAll({}, [op({}), op({ operationId: 'getAccount' })], 'accounts').exclude).toEqual([{ tag: ['accounts'] }])
    expect(turnOffAll({}, [op({}), op({ operationId: 'getAccount' })]).exclude).toEqual([{ operationId: 'listAccounts' }, { operationId: 'getAccount' }])
  })

  it('describes selectors in words', () => {
    expect(describeSelector({ path: '/internal/**' })).toBe('path /internal/**')
    expect(whyOff(op({ state: 'excluded', exclusion: { kind: 'access', reason: 'access: read-only' } }))).toBe('read only: no writes')
  })
})
