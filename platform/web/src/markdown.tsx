// A small Markdown renderer for the docs a package ships (packaging.md): headings, paragraphs, lists,
// fenced code, tables and inline code, bold and links. It builds React elements, never HTML from the
// text, so nothing in a document can run in the page. Links open only http(s) and relative targets.

import type { ReactNode } from 'react'

/** For a link's target, a handler that shows it in the page (another doc of the package), or nothing. */
type Open = (href: string) => (() => void) | undefined

function inline(text: string, key: string, open?: Open): ReactNode[] {
  const out: ReactNode[] = []
  const pattern = /(`[^`]+`)|(\*\*[^*]+\*\*)|(\[[^\]]+\]\([^)\s]+\))/g
  let last = 0
  let m: RegExpExecArray | null
  let i = 0
  while ((m = pattern.exec(text))) {
    if (m.index > last) out.push(text.slice(last, m.index))
    const token = m[0]
    const k = `${key}-${i++}`
    if (token.startsWith('`')) out.push(<code key={k}>{token.slice(1, -1)}</code>)
    else if (token.startsWith('**')) out.push(<b key={k}>{token.slice(2, -2)}</b>)
    else {
      const [, label, href] = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token)!
      const show = open?.(href)
      out.push(show ? <button key={k} type="button" className="link" onClick={show}>{label}</button> : /^(https?:\/\/|\.{0,2}\/|#|[\w-]+\.md)/.test(href) && !/^javascript:/i.test(href)
        ? <a key={k} href={href} target={href.startsWith('http') ? '_blank' : undefined} rel="noreferrer">{label}</a>
        : label)
    }
    last = m.index + token.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

const cells = (row: string) => row.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim())

export function Markdown({ text, open }: { text: string; open?: Open }) {
  const blocks: ReactNode[] = []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    const key = 'b' + i
    if (line.startsWith('```')) {
      const code: string[] = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) code.push(lines[i++])
      i++
      blocks.push(<pre key={key} className="code">{code.join('\n')}</pre>)
      continue
    }
    const heading = /^(#{1,4})\s+(.*)$/.exec(line)
    if (heading) {
      const level = Math.min(heading[1].length + 1, 5)
      const Tag = `h${level}` as 'h2' | 'h3' | 'h4' | 'h5'
      blocks.push(<Tag key={key}>{inline(heading[2], key, open)}</Tag>)
      i++
      continue
    }
    if (line.trim().startsWith('|') && i + 1 < lines.length && /^\s*\|?\s*:?-{2,}/.test(lines[i + 1])) {
      const head = cells(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trim().startsWith('|')) rows.push(cells(lines[i++]))
      blocks.push(
        <table key={key} className="table table-stage">
          <thead><tr>{head.map((c, j) => <th key={j}>{inline(c, `${key}h${j}`, open)}</th>)}</tr></thead>
          <tbody>{rows.map((r, n) => <tr key={n}>{r.map((c, j) => <td key={j}>{inline(c, `${key}r${n}c${j}`, open)}</td>)}</tr>)}</tbody>
        </table>,
      )
      continue
    }
    if (/^\s*([-*]|\d+\.)\s+/.test(line)) {
      const ordered = /^\s*\d+\./.test(line)
      const items: string[] = []
      while (i < lines.length && /^\s*([-*]|\d+\.)\s+/.test(lines[i])) {
        let item = lines[i++].replace(/^\s*([-*]|\d+\.)\s+/, '')
        while (i < lines.length && /^\s{2,}\S/.test(lines[i]) && !/^\s*([-*]|\d+\.)\s+/.test(lines[i])) item += ' ' + lines[i++].trim()
        items.push(item)
      }
      const List = ordered ? 'ol' : 'ul'
      blocks.push(<List key={key} className="md-list">{items.map((t, n) => <li key={n}>{inline(t, `${key}i${n}`, open)}</li>)}</List>)
      continue
    }
    if (line.trim() === '') {
      i++
      continue
    }
    const paragraph: string[] = []
    while (i < lines.length && lines[i].trim() !== '' && !/^(#{1,4}\s|```|\s*([-*]|\d+\.)\s+|\s*\|)/.test(lines[i])) paragraph.push(lines[i++])
    if (paragraph.length === 0) paragraph.push(lines[i++])
    blocks.push(<p key={key}>{inline(paragraph.join(' '), key, open)}</p>)
  }
  return <div className="markdown">{blocks}</div>
}
