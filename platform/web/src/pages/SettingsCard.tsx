import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { api, ApiError, type Change, type Manifest } from '../api'
import { PACKS, choices, packChange, packOn } from '../choices'
import { useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Segmented } from '../ui'
import { NoticesCard } from './Notices'

export interface Pending { label: string; change: Change; value?: string }

/**
 * Changes picked on a page, saved together as the server's next revision (like an upload, with the
 * author in Activity). Each page that owns settings keeps its own.
 */
export function usePendingEdits(id: string, area: string) {
  const { can } = useSession()
  const server = useServer(id)
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const editable = can('servers:write') && !server.data?.archived && !server.data?.system
  const base = server.data?.latestVersion ?? 0
  const save = useMutation({
    mutationFn: () => api.saveEdit(id, base, Object.values(pending).map((p) => p.change),
      (area + ': ' + Object.values(pending).map((p) => p.label).join('; ')).slice(0, 200)),
    onSuccess: async () => {
      setPending({})
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config', 'definition']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  /** `original`: the pick puts the setting back as saved, so nothing is pending for it any more. */
  const pick = (key: string, label: string, original: boolean, change: Change, value?: string) => {
    setPending((now) => {
      const next = { ...now }
      if (original) delete next[key]
      else next[key] = { label, change, value }
      return next
    })
    save.reset()
  }
  const variant = !!server.data?.parent
  return { pending, pick, save, editable, variant, base, discard: () => { setPending({}); save.reset() }, reload: () => queryClient.invalidateQueries({ queryKey: ['server', id] }) }
}

type Edits = ReturnType<typeof usePendingEdits>

export function SaveBar({ edits }: { edits: Edits }) {
  const count = Object.keys(edits.pending).length
  if (!edits.editable || (count === 0 && !edits.save.error)) return null
  return (
    <div className="list" style={{ gap: 8 }}>
      {count > 0 && (
        <div className="actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={edits.save.isPending} onClick={() => edits.save.mutate()}>
            Save as revision {edits.base + 1}</button>
          <button type="button" className="btn btn-sm" onClick={edits.discard}>Undo changes</button>
          <span className="hint">{count} unsaved change{count === 1 ? '' : 's'}</span>
        </div>
      )}
      <ErrorBox error={edits.save.error} />
      {edits.save.error instanceof ApiError && edits.save.error.status === 409 && (
        <p className="hint">Someone saved a newer revision. <button type="button" className="btn btn-sm" onClick={() => { edits.discard(); void edits.reload() }}>Reload</button></p>
      )}
    </div>
  )
}

/** Some of the server's plain-language choices (choices.ts), editable where the page owns them. */
export function ChoiceRows({ manifest, only, edits }: { manifest: Manifest; only: (choiceId: string) => boolean; edits: Edits }) {
  return (
    <div className="rows">
      {(manifest.apis && manifest.instances ? choices(manifest) : []).filter((c) => only(c.id)).map((c) => {
        const was = c.current(manifest)
        const now = edits.pending[c.id]?.value ?? was
        return (
          <div key={c.id} style={{ padding: '12px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
            <span><b style={{ fontSize: 14, fontWeight: 600 }}>{c.title}</b>{edits.pending[c.id] && <span className="tag" style={{ marginLeft: 8 }}>changed</span>}<br />
              <span className="hint">{c.hint}</span></span>
            {edits.editable ? (
              <Segmented label={c.title} value={now} options={c.options.map((o) => [o.value, o.label])}
                onChange={(v) => edits.pick(c.id, `${c.title}: ${c.options.find((o) => o.value === v)?.label ?? v}`, v === was, c.change(v), v)} />
            ) : <span className="tag" style={{ alignSelf: 'flex-start' }}>{c.options.find((o) => o.value === now)?.label ?? now}</span>}
          </div>
        )
      })}
    </div>
  )
}

/** Support packs: extra tools the package ships (audit, explanations, checks). */
export function PackRow({ manifest, edits }: { manifest: Manifest; edits: Edits }) {
  const variant = edits.variant || !!manifest.build?.extends
  return (
    <div style={{ padding: '12px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
      <span><b style={{ fontSize: 14, fontWeight: 600 }}>Extra tools in the package</b><br /><span className="hint">Support packs: audit, explanations, checks.</span></span>
      <div className="actions">
        {PACKS.map(([pack, label]) => {
          const key = 'pack:' + pack
          const saved = packOn(manifest, pack)
          const on = edits.pending[key] ? edits.pending[key].value === 'on' : saved
          return (
            <button key={pack} type="button" className="pill" aria-pressed={on} disabled={!edits.editable}
              onClick={() => edits.pick(key, `${label}: ${on ? 'off' : 'on'}`, !on === saved, packChange(pack, !on, variant), on ? 'off' : 'on')}>{label}</button>
          )
        })}
      </div>
    </div>
  )
}

/** A card of settings owned by the page it sits on, with its own save. */
export function SettingsCard({ id, title, children, edits }: { id: string; title: string; children: (m: Manifest) => ReactNode; edits: Edits }) {
  const manifest = useManifest(id)
  if (!manifest.data) return null
  return (
    <div className="card">
      <h2>{title}</h2>
      {children(manifest.data)}
      <SaveBar edits={edits} />
    </div>
  )
}

/** Notices added to tool descriptions: the server's own and shared sets from the Library. */
export function NoticesEditor({ id }: { id: string }) {
  const manifest = useManifest(id)
  const edits = usePendingEdits(id, 'Notices')
  const definition = useQuery({ queryKey: ['definition', id, edits.base], queryFn: () => api.definition(id, edits.base), enabled: edits.base > 0 && !!manifest.data, retry: false })
  if (!manifest.data) return null
  return (
    <div className="list" style={{ gap: 12 }}>
      <NoticesCard manifest={manifest.data} definition={definition.data} editable={edits.editable} pending={edits.pending}
        pick={(key, label, original, change) => edits.pick(key, label, original, change)} />
      <SaveBar edits={edits} />
    </div>
  )
}
