import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { api, type Tool } from '../api'
import { useServer } from '../serverData'
import { startingArguments } from '../tryArgs'
import { ErrorBox } from '../ui'

/**
 * Trying a tool (Tools & rules): the call goes to the server mcpgp packages, running against a mock
 * made from the spec on the platform, never to a real system. It shows what the agent gets back and
 * the HTTP request the server would send.
 */
export function TryTool({ id, tool }: { id: string; tool: Tool }) {
  const server = useServer(id)
  const base = server.data?.latestVersion ?? 0
  const [text, setText] = useState(() => JSON.stringify(startingArguments(tool.inputSchema), null, 2))
  let parsed: unknown = null
  let invalid: string | null = null
  try {
    parsed = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) invalid = 'The arguments are a JSON object.'
  } catch (e) {
    invalid = e instanceof Error ? e.message : 'not JSON'
  }
  const run = useMutation({ mutationFn: () => api.tryTool(id, base, tool.name, parsed) })
  const result = run.data?.result
  const structured = result?.structuredContent
  const textOut = result?.content?.find((c) => c.type === 'text')?.text

  return (
    <div className="stage-card" aria-label={`Try ${tool.name}`}>
      <span className="eyebrow">Try it against a mock</span>
      <p className="hint">The server runs on the platform against a mock made from the spec’s examples; no real system is called.
        The first try of a version starts it (a few seconds).</p>
      <label className="field">Arguments
        <textarea className="input input-stage" rows={Math.min(14, text.split('\n').length + 1)} style={{ padding: 10 }} value={text}
          onChange={(e) => { setText(e.target.value); run.reset() }} spellCheck={false} aria-invalid={!!invalid} />
      </label>
      {invalid && <span className="hint">{invalid}</span>}
      <div className="actions">
        <button type="button" className="btn btn-primary" disabled={!!invalid || run.isPending || base === 0} onClick={() => run.mutate()}>
          {run.isPending ? 'Calling…' : 'Call the tool'}</button>
      </div>
      <ErrorBox error={run.error} />
      {run.data && (
        <>
          {run.data.leftOut.length > 0 && (
            <div className="note">Custom code isn’t run on the platform; left out: {run.data.leftOut.join(', ')}. <span className="mono">mcpgp test</span> runs it.</div>
          )}
          {run.data.error && <div className="note note-bad">{run.data.error.message}</div>}
          {result && (
            <>
              <span className="eyebrow">{result.isError ? 'The agent gets an error' : 'The agent gets'}</span>
              <pre className="code" aria-label="Result">{structured !== undefined && structured !== null ? JSON.stringify(structured, null, 2) : textOut ?? ''}</pre>
            </>
          )}
          <span className="eyebrow">{run.data.requests.length === 0 ? 'Nothing was sent to the API' : 'Sent to the API (the mock)'}</span>
          {run.data.requests.map((r, i) => (
            <pre key={i} className="code" aria-label="Request">{`${r.method} ${r.path}${r.query ? '?' + r.query : ''}  → ${r.status}\n`
              + Object.entries(r.headers).map(([k, v]) => `${k}: ${v}`).join('\n') + (r.body ? `\n\n${r.body}` : '')}</pre>
          ))}
        </>
      )}
    </div>
  )
}
