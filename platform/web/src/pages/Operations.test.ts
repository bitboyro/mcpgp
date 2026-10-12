import { describe, expect, it } from 'vitest'
import { resourceOf } from './Operations'

describe('resourceOf', () => {
  it('is the first named segment of the path', () => {
    expect(resourceOf('/partners/{identifier}')).toBe('/partners')
    expect(resourceOf('/partners')).toBe('/partners')
    expect(resourceOf('/v1/partners/{id}/contacts')).toBe('/partners')
    expect(resourceOf('/api/v2.1/tests')).toBe('/tests')
    expect(resourceOf('/{tenant}/orders')).toBe('/orders')
    expect(resourceOf('/')).toBe('/')
  })
})
