import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type Detect, type Manifest } from '../api'
import { useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox } from '../ui'

/** The contract versions each API ships, and how a target server says which one it runs (API definitions). */
export function ShippedVersions({ id, manifest }: { id: string; manifest: Manifest }) {
  return (
    <>
      {manifest.apis.map((a) => (
        <div key={a.id} className="card">
          <div className="card-head">
            <h2>{a.title ?? a.id}: contracts shipped</h2>
            <span className="tag mono">{a.toolPrefix ?? a.id}_*</span>
          </div>
          {a.contracts.map((c) => (
            <div key={c.version} className="row">
              <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <b style={{ fontSize: 20, letterSpacing: '-0.02em' }}>{c.version}</b>
                <span><span style={{ fontSize: 13, fontWeight: 500 }}>{c.format}</span><br /><span className="meta">{c.basePath ?? '/'}{c.default ? ' · default' : ''}</span></span>
              </span>
            </div>
          ))}
          {a.contracts.length > 1 && <VersionDetection id={id} api={a.id} current={a.detect} />}
        </div>
      ))}
    </>
  )
}

type Where = 'field' | 'header' | 'body'

/**
 * How a target server says which version it runs, for customers' instances with `contract: auto`
 * (runtime-config §4.4): the definition's `apis[].detect`. Only with two or more versions shipped.
 */
function VersionDetection({ id, api: apiId, current }: { id: string; api: string; current?: Detect }) {
  const { can } = useSession()
  const queryClient = useQueryClient()
  const serverData = useServer(id)
  const editable = can('servers:write') && !serverData.data?.archived && !serverData.data?.system
  const [path, setPath] = useState(current?.path ?? '/version')
  const [under, setUnder] = useState(current ? !!current.underBasePath : true)
  const [where, setWhere] = useState<Where>(current?.header ? 'header' : current?.field || !current ? 'field' : 'body')
  const [field, setField] = useState(current?.field ?? '/version')
  const [header, setHeader] = useState(current?.header ?? 'X-Api-Version')
  const [pattern, setPattern] = useState(current?.pattern ?? '^(\\d+\\.\\d+)')
  const [refuse, setRefuse] = useState(current?.onFailure === 'refuse')
  const save = useMutation({
    mutationFn: (on: boolean) => {
      const rule: Detect = { method: current?.method ?? 'GET', path: path.trim(), pattern }
      if (under) rule.underBasePath = true
      if (where === 'field') rule.field = field.trim()
      if (where === 'header') rule.header = header.trim()
      if (refuse) rule.onFailure = 'refuse'
      return api.saveEdit(id, serverData.data?.latestVersion ?? 0, [on
        ? { op: 'set', path: `/apis/[id=${apiId}]/detect`, value: rule }
        : { op: 'remove', path: `/apis/[id=${apiId}]/detect` }],
        on ? 'Version detection: ' + rule.method + ' ' + rule.path : 'Version detection turned off')
    },
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const problem = !path.trim().startsWith('/') ? 'The path starts with /'
    : where === 'field' && !field.trim().startsWith('/') ? 'The field is a JSON pointer, starting with / (/version)'
    : where === 'header' && !/^[A-Za-z0-9-]+$/.test(header.trim()) ? 'The header is a name like X-Api-Version'
    : !validPattern(pattern) ? 'The pattern isn’t a valid regular expression' : null
  return (
    <div className="list" style={{ gap: 8, marginTop: 6 }}>
      <h2>Which version a customer’s server runs</h2>
      <p className="hint">
        When your customer leaves the version on automatic, the MCP server asks their system which version it runs, once when it starts.
        Without this, it uses the default version for every system. The answer is read here; it is never checked from the platform.
      </p>
      <dl className="kv">
        <dt>Asked</dt><dd>{current
          ? <span className="mono">{current.method} {current.path}{current.underBasePath ? ' under each version’s base path' : ''}</span>
          : 'No: the default version is used'}</dd>
      </dl>
      {editable && (
        <form className="list" style={{ gap: 8 }} onSubmit={(e) => { e.preventDefault(); save.mutate(true) }}>
          <label className="field"><span>Path that answers with the version</span>
            <input className="input mono" value={path} onChange={(e) => setPath(e.target.value)} />
          </label>
          <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}><input type="checkbox" checked={under} onChange={(e) => setUnder(e.target.checked)} />
            Each version answers under its own base path (<span className="mono">/api/v2/version</span>, <span className="mono">/api/v1/version</span>)</label>
          <label className="field"><span>Where the version is in the answer</span>
            <select className="input" value={where} onChange={(e) => setWhere(e.target.value as Where)}>
              <option value="field">A field in the JSON answer</option>
              <option value="header">A response header</option>
              <option value="body">The whole answer</option>
            </select>
          </label>
          {where === 'field' && <label className="field"><span>Field (JSON pointer)</span>
            <input className="input mono" value={field} onChange={(e) => setField(e.target.value)} /></label>}
          {where === 'header' && <label className="field"><span>Header</span>
            <input className="input mono" value={header} onChange={(e) => setHeader(e.target.value)} /></label>}
          <label className="field"><span>Pattern (its first group is the version)</span>
            <input className="input mono" value={pattern} onChange={(e) => setPattern(e.target.value)} />
          </label>
          <label className="field"><span>If the version can’t be found</span>
            <select className="input" value={refuse ? 'refuse' : 'useDefault'} onChange={(e) => setRefuse(e.target.value === 'refuse')}>
              <option value="useDefault">Use the default version</option>
              <option value="refuse">Refuse calls to that server until it can</option>
            </select>
          </label>
          {problem && <p className="hint" role="alert">{problem}</p>}
          <div className="actions">
            <button type="submit" className="btn btn-sm btn-primary" disabled={!!problem || save.isPending}>{current ? 'Save' : 'Turn on'}</button>
            {current && <button type="button" className="btn btn-sm" disabled={save.isPending} onClick={() => save.mutate(false)}>Turn off</button>}
          </div>
        </form>
      )}
      <ErrorBox error={save.error} />
    </div>
  )
}

function validPattern(pattern: string) {
  try {
    new RegExp(pattern)
    return pattern.length > 0
  } catch {
    return false
  }
}
