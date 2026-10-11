import { startingArguments } from './tryArgs'

describe('trying a tool', () => {
  it('starts with the required arguments and the mock instance', () => {
    expect(startingArguments({
      type: 'object', required: ['instance', 'path', 'body'],
      properties: {
        instance: { type: 'string', enum: [] },
        path: { type: 'object', required: ['id'], properties: { id: { type: 'string' } } },
        query: { type: 'object', properties: { notify: { type: 'boolean' } } },
        body: { type: 'object', required: ['amount', 'reason'], properties: { amount: { type: 'number', minimum: 0.01 }, reason: { enum: ['damaged', 'late'] } } },
      },
    })).toEqual({ instance: 'mock', path: { id: 'example' }, body: { amount: 0.01, reason: 'damaged' } })
    expect(startingArguments({ type: 'object', properties: { instance: { type: 'string' } } })).toEqual({ instance: 'mock' })
  })
})
