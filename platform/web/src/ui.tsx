import type { ReactNode } from 'react'
import {
  Activity, BookOpen, Check, Code, Copy, Download, ExternalLink, Eye, FileCode, Folder, GitCompare, HeartPulse, Home, Info, Library,
  LayoutDashboard, ListChecks, Lock, Package, PackageCheck, Play, Plug, Plus, RefreshCw, Rocket, Save, SlidersHorizontal, Trash2, Undo2,
  Upload, Wrench, X, Zap, type LucideIcon,
} from 'lucide-react'
import { ApiError, type Build } from './api'

// Icons: Lucide (ISC licence), except the mcpgp mark. One name per meaning, so a meaning keeps its icon everywhere.
const ICONS = {
  home: Home, library: Library, builds: PackageCheck, activity: Activity, api: Code, shape: SlidersHorizontal, tools: Wrench,
  connections: Plug, sources: Folder, package: Package, deliver: Download, close: X, changes: GitCompare, overview: LayoutDashboard,
  general: Info, definition: FileCode, lock: Lock, pulse: HeartPulse,
  save: Save, upload: Upload, download: Download, eye: Eye, play: Play, trash: Trash2, undo: Undo2, plus: Plus, check: Check,
  zap: Zap, book: BookOpen, refresh: RefreshCw, copy: Copy, external: ExternalLink, rocket: Rocket, checks: ListChecks,
} satisfies Record<string, LucideIcon>

export type IconName = keyof typeof ICONS | 'mark'

export function Icon({ name, size = 16, stroke = 'currentColor', width = 1.8 }: { name: IconName; size?: number; stroke?: string; width?: number }) {
  if (name === 'mark') {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth={width} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M4 7h7M4 12h16M13 17h7" />
      </svg>
    )
  }
  const Lucide = ICONS[name]
  return <Lucide size={size} color={stroke} strokeWidth={width} aria-hidden="true" />
}

/** A button's label with its icon in front: the icon names the action, the words say it. */
export function Label({ icon, children }: { icon: IconName; children: ReactNode }) {
  return <><Icon name={icon} />{children}</>
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
