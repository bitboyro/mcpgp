import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, type Change } from '../api'
import { useServer } from '../serverData'
import { useSession } from '../session'

/** A setting the operator supplies in the runtime config: shown with its key, never edited on the platform. */
export interface DeployedSetting { label: string; key: string; hint?: string }

/** Settings that belong to whoever runs the server (paths, endpoints, secrets, identity provider details). */
export function SetWhenDeployed({ title, items, note }: { title: string; items: DeployedSetting[]; note?: string }) {
  return (
    <div className="card">
      <div className="card-head">
        <h2>{title}</h2>
        <span className="tag">Set when deployed</span>
      </div>
      {note && <p className="hint">{note}</p>}
      <dl className="kv">
        {items.map((i) => (
          <div key={i.key} style={{ display: 'contents' }}>
            <dt>{i.label}</dt>
            <dd><span className="mono">{i.key}</span>{i.hint && <><br /><span className="hint">{i.hint}</span></>}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

/** Whether this server's definition can be edited here, and a save that makes the changes the next version. */
export function useDefinitionSave(id: string, describe: string) {
  const { can } = useSession()
  const server = useServer(id)
  const queryClient = useQueryClient()
  const editable = can('servers:write') && !server.data?.archived && !server.data?.system
  const save = useMutation({
    mutationFn: ({ changes, what }: { changes: Change[]; what: string }) =>
      api.saveEdit(id, server.data?.latestVersion ?? 0, changes, (describe + ': ' + what).slice(0, 200)),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  return { editable, save }
}
