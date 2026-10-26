import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api'
import { useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox } from '../ui'
import { Toggle } from './Execution'

/** Whether tools describe their results (the definition's `schemas.output`): part of what the agent sees, so on Tools. */
export function OutputSchemas({ id }: { id: string }) {
  const { can } = useSession()
  const server = useServer(id)
  const manifest = useManifest(id)
  const queryClient = useQueryClient()
  const editable = can('servers:write') && !server.data?.archived && !server.data?.system
  const on = manifest.data?.policies?.outputSchemas !== false
  const save = useMutation({
    mutationFn: (value: boolean) => api.saveEdit(id, server.data?.latestVersion ?? 0, [{ op: 'set', path: '/schemas/output', value }],
      'Tools: ' + (value ? 'describe each tool result' : 'no output schemas')),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  if (!manifest.data) return null
  return (
    <div className="card">
      <h2>Tool results</h2>
      <Toggle label="Describe each tool's result (output schema)" checked={on} disabled={!editable || save.isPending}
        onChange={(v) => save.mutate(v)}
        hint="Agents and clients see each result's fields before calling."
        more="Taken from the API definition and kept loose (fields may be missing or extra), because real answers often differ from it; a difference is logged, never turned into an error. Lists come back as items. Saved as the next revision." />
      <ErrorBox error={save.error} />
    </div>
  )
}
