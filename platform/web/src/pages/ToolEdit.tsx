import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError, type Change, type Tool } from '../api'
import { baseName, sameTool, ownDescription, overrideId, paramDescriptions, rulePath, setOverride, validBaseName, type Override } from '../descriptions'
import { useServer } from '../serverData'
import { ErrorBox, Icon } from '../ui'
import { agentView } from './Overview'

/**
 * Changing one tool's title, description and parameter descriptions (definition-format.md §5): the
 * page writes its own inline rule for the tool (`overrides.rules`, id `ui:…`), previewed and saved as
 * the next version; "Back to the spec" removes the rule. Notices stay as they are.
 */
export function ToolEdit({ id, tool, onClose }: { id: string; tool: Tool; onClose: () => void }) {
  const queryClient = useQueryClient()
  const server = useServer(id)
  const base = server.data?.latestVersion ?? 0
  const definition = useQuery({ queryKey: ['definition', id, base], queryFn: () => api.definition(id, base), enabled: base > 0 })
  const ruleId = overrideId(tool)
  const existing = definition.data?.root.overrides?.rules?.find((r) => r.id === ruleId)
  const now: Override = { title: tool.title ?? '', description: ownDescription(tool), params: paramDescriptions(tool), name: baseName(tool) }
  const [wanted, setWanted] = useState<Override>(now)
  const nameOk = wanted.name === undefined || validBaseName(wanted.name)
  const change = nameOk ? setOverride(tool, now, wanted, existing) : null
  const [changes, setChanges] = useState<Change[] | null>(null)
  const preview = useQuery({
    queryKey: ['edit-preview', id, base, changes],
    queryFn: () => api.previewEdit(id, base, changes!),
    enabled: !!changes && base > 0,
    retry: false,
  })
  // a renamed tool has a new name: find it by its operation
  const previewed = preview.data?.manifest?.tools.find((t) => sameTool(t, tool))
  const save = useMutation({
    mutationFn: (list: Change[]) => api.saveEdit(id, base, list, list[0].op === 'remove' ? `Tools: ${tool.name} back to the spec`
      : wanted.name !== now.name ? `Tools: ${tool.name} renamed (${wanted.name})` : `Tools: ${tool.name} title and descriptions`),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'definition', 'version', 'naming', 'operations']) await queryClient.invalidateQueries({ queryKey: [key] })
      onClose()
    },
  })
  const params = Object.keys(now.params)

  return (
    <form className="stage-card" aria-label={`Edit ${tool.name}`} onSubmit={(e) => { e.preventDefault(); if (change) save.mutate([change]) }}>
      <span className="eyebrow">Change what the agent reads</span>
      {now.name !== undefined && (
        <label className="field">Name, without the prefixes and suffix
          <input className="input input-stage mono" value={wanted.name ?? ''} aria-invalid={!nameOk}
            onChange={(e) => { setWanted({ ...wanted, name: e.target.value.trim() }); setChanges(null) }} />
          <span className="hint">{nameOk ? `Now ${tool.name}; the preview shows the new full name. Agents that call the old name need updating.`
            : 'Letters, digits and _, starting with a letter.'}</span>
        </label>
      )}
      <label className="field">Title
        <input className="input input-stage" value={wanted.title} onChange={(e) => { setWanted({ ...wanted, title: e.target.value }); setChanges(null) }} />
      </label>
      <label className="field">Description
        <textarea className="input input-stage" rows={5} style={{ padding: 10 }} value={wanted.description}
          onChange={(e) => { setWanted({ ...wanted, description: e.target.value }); setChanges(null) }} />
      </label>
      {tool.kind === 'api' && params.map((p) => (
        <label key={p} className="field"><span className="mono">{p}</span>
          <input className="input input-stage" value={wanted.params[p] ?? ''}
            onChange={(e) => { setWanted({ ...wanted, params: { ...wanted.params, [p]: e.target.value } }); setChanges(null) }} />
        </label>
      ))}
      <p className="hint">Saved as a rule in the definition ({ruleId}), shown under “Why it looks like this”. Notices are added on top.
        {tool.kind === 'api' && tool.operation?.key && ' It applies to every tool of this operation (content-type variants too).'}</p>
      {previewed && <pre className="code" aria-label="Preview">{JSON.stringify(agentView(previewed), null, 2)}</pre>}
      <ErrorBox error={save.error ?? preview.error} />
      {save.error instanceof ApiError && save.error.status === 409 && <p className="hint">Someone saved a newer version; close and open the tool again.</p>}
      <div className="actions">
        <button type="submit" className="btn btn-primary" disabled={!change || save.isPending}><Icon name="save" />Save as revision {base + 1}</button>
        <button type="button" className="btn btn-stage" disabled={!change} onClick={() => setChanges(change ? [change] : null)}>Preview</button>
        {existing && <button type="button" className="btn btn-stage" onClick={() => save.mutate([{ op: 'remove', path: rulePath(ruleId) }])}>Back to the spec</button>}
        <button type="button" className="btn btn-stage" onClick={onClose}>Cancel</button>
      </div>
    </form>
  )
}
