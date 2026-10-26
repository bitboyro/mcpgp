import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type Change } from '../api'
import { useManifest, useServer } from '../serverData'
import { ErrorBox, Help, Icon, Loading, PageHead, ago } from '../ui'
import { useDefinitionSave } from './Deployed'
import { BuildButton } from './Overview'

/** General: what the MCP server is, as connecting agents see it in initialize, and where its definition stands. */
export function GeneralPage() {
  const { id = '' } = useParams()
  const server = useServer(id)
  const manifest = useManifest(id)
  const latest = server.data?.versions.at(-1)
  return (
    <>
      <PageHead title="General" sub="What this MCP server is, and what agents are told when they connect.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={server.error ?? manifest.error} />
          {(server.isLoading || manifest.isLoading) && <Loading />}
          {manifest.data && (
            <div className="card">
              <h2>Identity</h2>
              <dl className="kv">
                <dt>Name</dt><dd className="mono">{manifest.data.server.name}</dd>
                <dt>Title</dt><dd>{manifest.data.server.title ?? '—'}</dd>
                <dt>Server version</dt><dd className="mono">{manifest.data.server.version}</dd>
              </dl>
              <p className="hint">Sent to agents as the server's info when they connect.</p>
            </div>
          )}
          {manifest.data && <InstructionsEditor id={id} />}
          {server.data && (
            <div className="card">
              <h2>Definition</h2>
              <dl className="kv">
                <dt>Server</dt><dd className="mono">{manifest.data?.server.name ?? id} {manifest.data?.server.version}</dd>
                <dt>Revision</dt><dd>{latest ? <>{latest.number} · by {latest.author} {ago(latest.created)}{latest.message ? ` · ${latest.message}` : ''}</> : 'none yet'}</dd>
                {server.data.parent && <><dt>Variant of</dt><dd><Link to={`/servers/${server.data.parent}`}>{server.data.parent}</Link></dd></>}
                {manifest.data?.build.extends && <><dt>Extends</dt><dd className="mono">{manifest.data.build.extends.name}{manifest.data.build.extends.version ? '@' + manifest.data.build.extends.version : ''}</dd></>}
              </dl>
              <p className="hint">Each saved change becomes a new revision, like an upload, with your name in Activity. Comments and layout in the file stay as they are.</p>
            </div>
          )}
        </section>
      </div>
    </>
  )
}

/**
 * The instructions agents receive on initialize (metadata.instructions, kept as written). The preview is the
 * compiler's answer for the text being typed, so it shows exactly what gets sent, notes included.
 */
function InstructionsEditor({ id }: { id: string }) {
  const server = useServer(id)
  const manifest = useManifest(id)
  const base = server.data?.latestVersion ?? 0
  const definition = useQuery({ queryKey: ['definition', id, base], queryFn: () => api.definition(id, base), enabled: base > 0, retry: false })
  const meta = (definition.data?.root as { metadata?: { instructions?: string; description?: string } } | undefined)?.metadata
  const saved = meta?.instructions ?? ''
  const [text, setText] = useState(saved)
  useEffect(() => setText(saved), [saved])
  const [typed, setTyped] = useState(text)
  useEffect(() => { const t = setTimeout(() => setTyped(text), 400); return () => clearTimeout(t) }, [text])
  const { editable, save } = useDefinitionSave(id, 'General')
  const change: Change | null = text.trim() === saved.trim() ? null
    : text.trim() ? { op: 'set', path: '/metadata/instructions', value: text.trim() } : { op: 'remove', path: '/metadata/instructions' }
  const typedChange: Change | null = typed.trim() === saved.trim() ? null
    : typed.trim() ? { op: 'set', path: '/metadata/instructions', value: typed.trim() } : { op: 'remove', path: '/metadata/instructions' }
  const preview = useQuery({
    queryKey: ['instructions-preview', id, base, typedChange],
    queryFn: () => api.previewEdit(id, base, [typedChange!]),
    enabled: !!typedChange && base > 0,
    placeholderData: keepPreviousData,
    retry: false,
  })
  const sent = typedChange ? preview.data?.manifest?.server.instructions : manifest.data?.server.instructions
  return (
    <div className="card">
      <h2>Instructions for agents</h2>
      <Help text="What agents are told about this server when they connect."
        more="Sent in the MCP initialize response; each client decides how to use them, often as part of the agent's context. Tool descriptions and notices are separate, on Tools. Without instructions, agents get the description on one line." />
      <label className="field"><span className="visually-hidden">Instructions for agents</span>
        <textarea className="input" rows={8} value={text} disabled={!editable} onChange={(e) => { setText(e.target.value); save.reset() }}
          placeholder={'What this server is for, and how to use it well.\n\n- Search before listing.\n- Ask before changing data.'}
          style={{ padding: 12, minHeight: 160, resize: 'vertical', lineHeight: 1.5 }} />
      </label>
      {!text.trim() && meta?.description && <p className="hint">Empty: agents get the description, “{meta.description.trim().replace(/\s+/g, ' ')}”.</p>}
      <div className="stage-card" aria-label="What agents receive" style={{ background: 'var(--stage)', color: 'var(--stage-ink)' }}>
        <span className="eyebrow">What agents receive{typedChange ? (preview.isFetching ? ' · updating…' : ' · unsaved') : ''}</span>
        {sent ? <p style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 14 }}>{sent}</p> : <p className="hint" style={{ margin: 0 }}>No instructions.</p>}
      </div>
      {preview.error && <ErrorBox error={preview.error} />}
      {editable && change && (
        <div className="actions">
          <button type="button" className="btn btn-primary btn-sm" disabled={save.isPending}
            onClick={() => save.mutate({ changes: [change], what: text.trim() ? 'instructions for agents' : 'instructions removed' })}>
            <Icon name="save" />Save as revision {base + 1}</button>
          <button type="button" className="btn btn-sm" onClick={() => setText(saved)}><Icon name="undo" />Undo changes</button>
        </div>
      )}
      <ErrorBox error={save.error} />
    </div>
  )
}

