import { render } from '@testing-library/react'
import { Markdown } from './markdown'

describe('package docs', () => {
  it('renders headings, lists, code, tables and inline marks', () => {
    const { container } = render(<Markdown text={[
      '# Install orders-mcp', '', 'Needs **Java 21**. Run `java -jar orders-mcp.jar`.', '', '1. Copy the file', '2. Fill in', '   the config', '',
      '```bash', 'java -jar orders-mcp.jar --config x.yaml', '```', '', '| Key | Meaning |', '|---|---|', '| `server.port` | port |',
      '', 'See [Configuration](CONFIGURATION.md).',
    ].join('\n')} />)
    expect(container.querySelector('h2')!.textContent).toBe('Install orders-mcp')
    expect(container.querySelector('p b')!.textContent).toBe('Java 21')
    expect(container.querySelector('p code')!.textContent).toBe('java -jar orders-mcp.jar')
    expect([...container.querySelectorAll('ol li')].map((li) => li.textContent)).toEqual(['Copy the file', 'Fill in the config'])
    expect(container.querySelector('pre')!.textContent).toBe('java -jar orders-mcp.jar --config x.yaml')
    expect(container.querySelector('td code')!.textContent).toBe('server.port')
    expect(container.querySelector('a')!.getAttribute('href')).toBe('CONFIGURATION.md')
  })

  it('never turns the text into HTML or script links', () => {
    const { container } = render(<Markdown text={'<img src=x onerror=alert(1)> [x](javascript:alert(1))'} />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('a')).toBeNull()
    expect(container.textContent).toContain('<img src=x onerror=alert(1)>')
  })
})

describe('links between docs', () => {
  it('shows another doc of the package in place', () => {
    const shown: string[] = []
    const { container } = render(<Markdown text="See [Configuration](CONFIGURATION.md) and [site](https://example.com)."
      open={(href) => (href.endsWith('.md') ? () => shown.push(href) : undefined)} />)
    container.querySelector('button')!.click()
    expect(shown).toEqual(['CONFIGURATION.md'])
    expect(container.querySelector('a')!.getAttribute('href')).toBe('https://example.com')
  })
})
