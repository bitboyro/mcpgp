import { ApiError } from './api'
import { idFrom, mergeFiles, specOnly, specProblems } from './specUpload'

describe('uploading a spec', () => {
  it('adds the files picked later, and a zip replaces them', () => {
    const first = mergeFiles(null, { 'api.json': '{}' })
    expect(mergeFiles(first, { 'definitions/entry.json': '{}' })).toEqual({ 'api.json': '{}', 'definitions/entry.json': '{}' })
    const zip = new Blob(['zip'])
    expect(mergeFiles(first, zip)).toBe(zip)
    expect(specOnly({ 'api.json': '{}', 'README.md': '#', 'a.YML': 'x' })).toEqual({ 'api.json': '{}', 'a.YML': 'x' })
  })

  it('reads what an upload lacks from the error', () => {
    const error = new ApiError(422, 'SPEC_PROBLEMS', 'definitions/entry.json is missing', {
      missing: [{ file: 'definitions/entry.json', from: 'api.json', pointer: '/definitions/Entry/$ref' }],
    })
    expect(specProblems(error)?.missing?.[0].file).toBe('definitions/entry.json')
    expect(specProblems(new ApiError(422, 'VALIDATION', 'x'))).toBeNull()
    expect(idFrom('Orders API (v2)')).toBe('orders-api-v2')
  })
})
