import type { ReactNode } from 'react'
import { ApiError, type Build } from './api'

const paths: Record<string, ReactNode> = {
  home: <path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />,
  library: <path d="M4 4h16v16H4zM4 10h16M10 4v16" />,
  builds: <path d="M3 4h18v4H3zM5 8v12h14V8M10 12h4" />,
  activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
  api: <path d="M8 6l-6 6 6 6M16 6l6 6-6 6" />,
  shape: <><path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12" /><circle cx="16" cy="6" r="2" /><circle cx="10" cy="12" r="2" /><circle cx="18" cy="18" r="2" /></>,
  tools: <path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.5-2.5z" />,
  connections: <path d="M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0zM12 18v4" />,
  sources: <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />,
  package: <path d="M21 8l-9-5-9 5 9 5 9-5zM3 8v8l9 5 9-5V8M12 13v8" />,
  deliver: <path d="M12 3v12M7 10l5 5 5-5M4 19h16" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  mark: <path d="M4 7h7M4 12h16M13 17h7" />,
}

export function Icon({ name, size = 16, stroke = 'currentColor', width = 1.8 }: { name: keyof typeof paths; size?: number; stroke?: string; width?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  )
}

export type Tone = 'ok' | 'warn' | 'bad' | 'idle' | 'new'

export function Chip({ tone, children }: { tone: Tone; children: ReactNode }) {
  return <span className={'chip chip-' + tone}>{children}</span>
}

export function buildTone(build: Pick<Build, 'status' | 'released' | 'archived' | 'deleted'>): [Tone, string] {
  if (build.deleted) return ['idle', 'Files deleted']
  if (build.status === 'failed') return ['bad', 'Failed']
  if (build.status === 'queued') return ['idle', 'Queued']
  if (build.status === 'running') return ['new', 'Building']
  if (build.released) return ['ok', build.archived ? 'Released · archived' : 'Released']
  return ['ok', build.archived ? 'Archived' : 'Built']
}

export function BuildChip({ build }: { build: Pick<Build, 'status' | 'released' | 'archived' | 'deleted'> }) {
  const [tone, label] = buildTone(build)
  return <Chip tone={tone}>{label}</Chip>
}

export function Segmented<T extends string>({ label, value, options, onChange, role = 'radio' }: {
  label: string
  value: T
  options: [T, string][]
  onChange: (value: T) => void
  role?: 'radio' | 'tab'
}) {
  return (
    <div className="segmented" role={role === 'radio' ? 'radiogroup' : 'tablist'} aria-label={label}>
      {options.map(([v, text]) => (
        <button key={v} type="button" role={role} {...(role === 'radio' ? { 'aria-checked': v === value } : { 'aria-selected': v === value })}
          onClick={() => onChange(v)}>{text}</button>
      ))}
    </div>
  )
}

export function ErrorBox({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof ApiError ? error.message : error instanceof Error ? error.message : String(error)
  return <div className="error" role="alert">{message}</div>
}

export function Loading({ what }: { what?: string }) {
  return <div className="empty" role="status">Loading{what ? ' ' + what : ''}…</div>
}

export function PageHead({ title, sub, children }: { title: string; sub?: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {children && <div className="actions">{children}</div>}
    </header>
  )
}

const units: [number, Intl.RelativeTimeFormatUnit][] = [[60, 'second'], [60, 'minute'], [24, 'hour'], [30, 'day'], [12, 'month'], [Infinity, 'year']]
const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })

export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—'
  let delta = (new Date(iso).getTime() - now) / 1000
  for (const [size, unit] of units) {
    if (Math.abs(delta) < size) return relative.format(Math.round(delta), unit)
    delta /= size
  }
  return iso
}

export function bytes(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

export function shortDigest(digest: string | undefined): string {
  if (!digest) return '—'
  const hex = digest.replace(/^sha256:/, '')
  return 'sha256:' + hex.slice(0, 4) + '…' + hex.slice(-4)
}

/** Lowercase letters, digits and '-', as server ids need; a trailing '-' stays, so "Orders Lite" can be typed. */
export function slug(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+/, '')
}
