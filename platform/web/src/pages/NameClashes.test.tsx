import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { NameClashesView } from './SpecUpload'

const clash = {
  operation: 'tc:PUT:/tests/{identifier}', method: 'PUT', path: '/tests/{identifier}', operationId: 'partnerIdentifierPut',
  name: 'tc_partner_identifier_put', suggested: 'tc_test_replace', base: 'test_replace',
}

describe('clashing tool names on a spec upload', () => {
  it('takes the suggested or edited names, or the rule', async () => {
    const onRenames = vi.fn()
    const onRule = vi.fn()
    render(<NameClashesView clashes={[clash]} prefix="tc" onRenames={onRenames} onRule={onRule} />)
    expect(screen.getByText('Two operations would get the same tool name.')).toBeTruthy()
    const input = screen.getByDisplayValue('test_replace')
    await userEvent.clear(input)
    await userEvent.type(input, 'test_update')
    await userEvent.click(screen.getByRole('button', { name: 'Use these names' }))
    expect(onRenames).toHaveBeenCalledWith({ 'tc:PUT:/tests/{identifier}': 'test_update' })

    await userEvent.clear(input)
    await userEvent.type(input, '9lives')
    expect((screen.getByRole('button', { name: 'Use these names' }) as HTMLButtonElement).disabled).toBe(true)
    await userEvent.click(screen.getByRole('button', { name: 'Always name clashing tools from their method and path' }))
    expect(onRule).toHaveBeenCalled()
  })
})
