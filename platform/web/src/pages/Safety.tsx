import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type Manifest } from '../api'
import { useManifest, useServer } from '../serverData'
import { useSession } from '../session'
import { ErrorBox, Loading, PageHead } from '../ui'
import { BuildButton } from './Overview'

/** The settings, as the definition's `safety` section holds them (all on by default). */
interface Settings {
  confirmDestructive: boolean
  responseLimitKb: number
  cleanTruncation: boolean
  inputLimits: boolean
  requestKb: number
  stringMaxLength: number
  rateLimits: boolean
  concurrent: number
  perMinute: number
  retryAfter: boolean
  batchEnabled: boolean
  batchMaxCalls: number
  batchConcurrency: number
  batchAllowWrites: boolean
  batchDeduplicateRequests: boolean
  requestReceiptTtlSeconds: number
  maxStoredReceipts: number
  resultMode: 'inline' | 'auto' | 'reference'
  captureLimitKb: number
  resultTtl: number
  maxStoredKb: number
  outputSchemas: boolean
}

function current(p: Manifest['policies']): Settings {
  return {
    confirmDestructive: p.confirmDestructive !== false,
    responseLimitKb: p.responseLimitKb ?? 256,
    cleanTruncation: p.cleanTruncation !== false,
    inputLimits: p.inputLimits?.enabled !== false,
    requestKb: p.inputLimits?.requestKb ?? 1024,
    stringMaxLength: p.inputLimits?.stringMaxLength ?? 65536,
    rateLimits: p.rateLimits?.enabled !== false,
    concurrent: p.rateLimits?.concurrent ?? 8,
    perMinute: p.rateLimits?.perMinute ?? 120,
    retryAfter: p.rateLimits?.retryAfter !== false,
    batchEnabled: p.batch?.enabled ?? false,
    batchMaxCalls: p.batch?.maxCalls ?? 20,
    batchConcurrency: p.batch?.concurrency ?? 4,
    batchAllowWrites: p.batch?.allowWrites ?? false,
    batchDeduplicateRequests: p.batch?.deduplicateRequests ?? false,
    requestReceiptTtlSeconds: p.batch?.requestReceiptTtlSeconds ?? 604800,
    maxStoredReceipts: p.batch?.maxStoredReceipts ?? 100000,
    resultMode: p.results?.mode ?? 'inline',
    captureLimitKb: p.results?.captureLimitKb ?? 4096,
    resultTtl: p.results?.ttlSeconds ?? 900,
    maxStoredKb: p.results?.maxStoredKb ?? 65536,
    outputSchemas: p.outputSchemas !== false,
  }
}

const PATHS: Record<keyof Settings, string> = {
  confirmDestructive: '/safety/confirmDestructive',
  responseLimitKb: '/safety/responseLimitKb',
  cleanTruncation: '/safety/cleanTruncation',
  inputLimits: '/safety/inputLimits/enabled',
  requestKb: '/safety/inputLimits/requestKb',
  stringMaxLength: '/safety/inputLimits/stringMaxLength',
  rateLimits: '/safety/rateLimits/enabled',
  concurrent: '/safety/rateLimits/concurrent',
  perMinute: '/safety/rateLimits/perMinute',
  retryAfter: '/safety/rateLimits/retryAfter',
  batchEnabled: '/batch/enabled',
  batchMaxCalls: '/batch/maxCalls',
  batchConcurrency: '/batch/concurrency',
  batchAllowWrites: '/batch/allowWrites',
  batchDeduplicateRequests: '/batch/deduplicateRequests',
  requestReceiptTtlSeconds: '/batch/requestReceiptTtlSeconds',
  maxStoredReceipts: '/batch/maxStoredReceipts',
  resultMode: '/results/mode',
  captureLimitKb: '/results/captureLimitKb',
  resultTtl: '/results/ttlSeconds',
  maxStoredKb: '/results/maxStoredKb',
  outputSchemas: '/schemas/output',
}

/**
 * Security & safety: what the server protects by default, each one something the builder can tune
 * or turn off. Saved in the definition's `safety` section, as the server's next version.
 */
export function SafetyPage() {
  const { id = '' } = useParams()
  const manifest = useManifest(id)
  return (
    <>
      <PageHead title="Security & safety" sub="Configure safeguards, batching, and large results. These work with every discovery mode.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={manifest.error} />
          {manifest.isLoading && <Loading />}
          {manifest.data && <SafetyForm id={id} saved={current(manifest.data.policies)} />}
        </section>
        <section className="stage" aria-label="Always on">
          <AlwaysOn />
        </section>
      </div>
    </>
  )
}

function SafetyForm({ id, saved }: { id: string; saved: Settings }) {
  const { can } = useSession()
  const server = useServer(id)
  const queryClient = useQueryClient()
  const editable = can('servers:write') && !server.data?.archived && !server.data?.system
  const [s, setS] = useState(saved)
  useEffect(() => setS(saved), [JSON.stringify(saved)]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) => setS((old) => ({ ...old, [key]: value }))
  const changed = (Object.keys(PATHS) as (keyof Settings)[]).filter((k) => s[k] !== saved[k])
  const invalid = (['responseLimitKb', 'requestKb', 'stringMaxLength', 'concurrent', 'perMinute', 'batchMaxCalls', 'batchConcurrency', 'requestReceiptTtlSeconds', 'maxStoredReceipts', 'captureLimitKb', 'resultTtl', 'maxStoredKb'] as const)
    .find((k) => !Number.isInteger(s[k]) || s[k] < (({ requestReceiptTtlSeconds: 60 } as Record<string, number>)[k] ?? 1) || s[k] > (({ batchMaxCalls: 100, batchConcurrency: 16, requestReceiptTtlSeconds: 2592000, maxStoredReceipts: 1000000, captureLimitKb: 65536, resultTtl: 86400, maxStoredKb: 262144 } as Record<string, number>)[k] ?? Number.MAX_SAFE_INTEGER))
  const save = useMutation({
    mutationFn: () => api.saveEdit(id, server.data?.latestVersion ?? 0,
      changed.map((k) => ({ op: 'set' as const, path: PATHS[k], value: s[k] })), 'Security & safety: ' + changed.join(', ')),
    onSuccess: async () => {
      for (const key of ['server', 'manifest', 'servers', 'version', 'example-config']) await queryClient.invalidateQueries({ queryKey: [key] })
    },
  })
  const off = !editable
  return (
    <form className="list" style={{ gap: 16 }} onSubmit={(e) => { e.preventDefault(); save.mutate() }}>
      <div className="card">
        <h2>Changing and deleting data</h2>
        <Toggle label="Agents confirm before a tool deletes or changes data" checked={s.confirmDestructive} disabled={off}
          onChange={(v) => set('confirmDestructive', v)}
          hint="The agent gets a confirmation step first, so an injected or mistaken instruction can't delete data in one call." />
      </div>

      <div className="card">
        <h2>Large answers</h2>
        <NumberField label="Largest answer an agent gets (KB)" value={s.responseLimitKb} disabled={off} onChange={(v) => set('responseLimitKb', v)}
          hint="The inline output budget. Retention can save larger responses for retrieval; otherwise long answers are cut." />
        <Toggle label="Cut long lists at whole items" checked={s.cleanTruncation} disabled={off} onChange={(v) => set('cleanTruncation', v)}
          hint="The agent keeps valid data (as many items as fit) and is told how many were left out, instead of JSON cut in the middle." />
      </div>

      <div className="card">
        <h2>Batch execution</h2>
        <Toggle label="Enable batch calls" checked={s.batchEnabled} disabled={off} onChange={(v) => set('batchEnabled', v)}
          hint="Adds batch_call alongside direct and discovery tools. Calls are independent; sequential execution stops on failure." />
        <NumberField label="Maximum operations per batch" value={s.batchMaxCalls} disabled={off || !s.batchEnabled} onChange={(v) => set('batchMaxCalls', v)} hint="1–100 operations." />
        <NumberField label="Parallel read operations" value={s.batchConcurrency} disabled={off || !s.batchEnabled} onChange={(v) => set('batchConcurrency', v)} hint="1–16 concurrent reads. Existing per-instance rate limits also apply." />
        <Toggle label="Allow writes in sequential batches" checked={s.batchAllowWrites} disabled={off || !s.batchEnabled} onChange={(v) => set('batchAllowWrites', v)}
          hint="POST, PUT, PATCH and DELETE retain their validation and confirmation requirements. Earlier writes remain committed after a failure. No automatic retries or rollback." />
        <Toggle label="Deduplicate retried batches" checked={s.batchDeduplicateRequests} disabled={off || !s.batchEnabled} onChange={(v) => set('batchDeduplicateRequests', v)}
          hint="A caller can reuse a requestId to recover a recorded batch outcome without sending it upstream again. An interrupted batch reports an unknown outcome so writes are not replayed." />
        <NumberField label="Request receipt lifetime (seconds)" value={s.requestReceiptTtlSeconds} disabled={off || !s.batchEnabled || !s.batchDeduplicateRequests} onChange={(v) => set('requestReceiptTtlSeconds', v)} hint="60 seconds to 30 days; completed receipts expire after this time. Unfinished receipts stay protected as unknown outcomes." />
        <NumberField label="Maximum stored request receipts" value={s.maxStoredReceipts} disabled={off || !s.batchEnabled || !s.batchDeduplicateRequests} onChange={(v) => set('maxStoredReceipts', v)} hint="1–1,000,000 receipts. Expired completed receipts are removed before new receipts are accepted." />
      </div>

      <div className="card">
        <h2>Retained results</h2>
        <label className="field"><span>Result delivery</span>
          <select className="input" value={s.resultMode} disabled={off} onChange={(e) => set('resultMode', e.target.value as Settings['resultMode'])}>
            <option value="inline">Inline (existing truncation)</option>
            <option value="auto">Save large results automatically</option>
            <option value="reference">Always return a result reference</option>
          </select>
        </label>
        <p className="hint">Independent of batching and discovery. Adds result_get to read saved responses without repeating the operation. Results live in server memory, are private to a session, and expire or disappear on restart or reload. Full means one response, not every upstream page.</p>
        <NumberField label="Maximum captured response (KB)" value={s.captureLimitKb} disabled={off || s.resultMode === 'inline'} onChange={(v) => set('captureLimitKb', v)} hint="1–65536 KB. Responses beyond this limit are reported as incomplete." />
        <NumberField label="Result lifetime (seconds)" value={s.resultTtl} disabled={off || s.resultMode === 'inline'} onChange={(v) => set('resultTtl', v)} hint="1–86400 seconds." />
        <NumberField label="Result storage budget (KB)" value={s.maxStoredKb} disabled={off || s.resultMode === 'inline'} onChange={(v) => set('maxStoredKb', v)} hint="1–262144 KB. Shared by API responses and batch responses." />
      </div>

      <div className="card">
        <h2>Tool results</h2>
        <Toggle label="Describe each tool's result (output schema)" checked={s.outputSchemas} disabled={off}
          onChange={(v) => set('outputSchemas', v)}
          hint="From the API's spec, so agents and clients know the fields before calling. Kept loose (fields may be missing or extra), because real answers often differ from the spec; a difference is logged, never turned into an error. Lists come back as items." />
      </div>

      <div className="card">
        <h2>What agents send</h2>
        <Toggle label="Limit the size of what agents send" checked={s.inputLimits} disabled={off} onChange={(v) => set('inputLimits', v)}
          hint="Refuses oversized calls before anything reaches the target server. Files sent inline have their own limit, set by whoever runs the server." />
        <NumberField label="Largest tool call (KB)" value={s.requestKb} disabled={off || !s.inputLimits} onChange={(v) => set('requestKb', v)} />
        <NumberField label="Longest text value (characters)" value={s.stringMaxLength} disabled={off || !s.inputLimits}
          onChange={(v) => set('stringMaxLength', v)} hint="For fields whose spec sets no maximum length; a limit in the spec always applies." />
      </div>

      <div className="card">
        <h2>Calls to the target server</h2>
        <Toggle label="Limit calls to each target server" checked={s.rateLimits} disabled={off} onChange={(v) => set('rateLimits', v)}
          hint="Protects your customers' systems from an agent stuck in a loop. Whoever runs the server can change these for their own target server." />
        <NumberField label="Calls at once" value={s.concurrent} disabled={off || !s.rateLimits} onChange={(v) => set('concurrent', v)}
          hint="More wait for a free slot (up to 30 seconds)." />
        <NumberField label="Calls per minute" value={s.perMinute} disabled={off || !s.rateLimits} onChange={(v) => set('perMinute', v)}
          hint="Over this, the agent is told how long to wait." />
        <Toggle label="When the target server asks to slow down, tell the agent how long to wait" checked={s.retryAfter} disabled={off}
          onChange={(v) => set('retryAfter', v)} hint="Passes on the target's Retry-After with its 429 answer." />
      </div>

      {editable && (
        <div className="actions">
          <button type="submit" className="btn btn-primary" disabled={changed.length === 0 || !!invalid || save.isPending}>Save as the next version</button>
          {changed.length > 0 && <button type="button" className="btn" onClick={() => setS(saved)}>Undo changes</button>}
          {invalid && <span className="hint" role="alert">Numbers are whole and at least 1</span>}
        </div>
      )}
      <ErrorBox error={save.error} />
    </form>
  )
}

function Toggle({ label, checked, disabled, onChange, hint }: {
  label: string; checked: boolean; disabled: boolean; onChange: (v: boolean) => void; hint?: string
}) {
  return (
    <div className="list" style={{ gap: 2 }}>
      <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 14, fontWeight: 500 }}>
        <input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} /> {label}
      </label>
      {hint && <p className="hint" style={{ margin: '0 0 0 24px' }}>{hint}</p>}
    </div>
  )
}

function NumberField({ label, value, disabled, onChange, hint }: {
  label: string; value: number; disabled: boolean; onChange: (v: number) => void; hint?: string
}) {
  return (
    <div className="list" style={{ gap: 2, marginLeft: 24 }}>
      <label className="field">
        <span>{label}</span>
        <input className="input" type="number" min={1} step={1} value={Number.isNaN(value) ? '' : value} disabled={disabled}
          onChange={(e) => onChange(e.target.value === '' ? NaN : Number(e.target.value))} style={{ maxWidth: 180 }} />
      </label>
      {hint && <span className="hint">{hint}</span>}
    </div>
  )
}

/** Protections without a switch: they keep the server correct, so nothing turns them off. */
function AlwaysOn() {
  const items: [string, string][] = [
    ['Credentials stay on the server', 'Sent only to the target server they belong to; never shown to agents, written to logs or put in results.'],
    ['Secrets are masked', 'In results, errors, the audit log and recordings, with the fields you list under redaction.'],
    ['No redirects to other hosts', 'A redirect from the target is not followed, so credentials never travel elsewhere.'],
    ['Parameters stay parameters', 'Path values are encoded and can’t leave the path; cookie values are encoded; header values can’t contain line breaks.'],
    ['Files only from allowed places', 'Files by path are off unless turned on, confined to one folder, without traversal or symlink escapes.'],
    ['Tool results are data', 'The agent docs in the package tell agents never to follow instructions found in results.'],
  ]
  return (
    <div className="card">
      <h2>Always on</h2>
      <div className="list" style={{ gap: 10 }}>
        {items.map(([title, text]) => (
          <div key={title}><b style={{ fontSize: 14 }}>{title}</b><br /><span className="hint">{text}</span></div>
        ))}
      </div>
    </div>
  )
}
