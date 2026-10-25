import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { Change, Manifest } from '../api'
import { useManifest } from '../serverData'
import { ErrorBox, Loading, PageHead, Icon } from '../ui'
import { SetWhenDeployed, useDefinitionSave } from './Deployed'
import { NumberField, Toggle } from './Execution'
import { BuildButton, CustomerPreview } from './Overview'

/** The defaults the package ships with; where the definition sets none, the runtime's own (runtime-config §4). */
interface Settings {
  auditEnabled: boolean
  rotate: string
  retainDays: number
  arguments: string
  logLevel: string
  telemetry: boolean
  exporter: string
  serviceName: string
  traces: boolean
  metrics: boolean
}

function current(m: Manifest): Settings {
  const d = m.policies.runtimeDefaults ?? {}
  const signals = d.telemetry?.signals ?? ['traces', 'metrics']
  return {
    auditEnabled: d.audit?.file?.enabled ?? true,
    rotate: d.audit?.file?.rotate ?? 'daily',
    retainDays: d.audit?.file?.retainDays ?? 30,
    arguments: d.audit?.arguments ?? 'redacted',
    logLevel: d.configuration?.logging?.level ?? 'info',
    telemetry: d.telemetry?.enabled ?? false,
    exporter: d.telemetry?.exporter ?? 'otlp',
    serviceName: d.telemetry?.serviceName ?? '',
    traces: signals.includes('traces'),
    metrics: signals.includes('metrics'),
  }
}

const PATHS: Partial<Record<keyof Settings, string>> = {
  auditEnabled: '/runtimeDefaults/audit/file/enabled',
  rotate: '/runtimeDefaults/audit/file/rotate',
  retainDays: '/runtimeDefaults/audit/file/retainDays',
  arguments: '/runtimeDefaults/audit/arguments',
  logLevel: '/runtimeDefaults/configuration/logging/level',
  telemetry: '/runtimeDefaults/telemetry/enabled',
  exporter: '/runtimeDefaults/telemetry/exporter',
}

const LABELS: Record<keyof Settings, string> = {
  auditEnabled: 'audit file', rotate: 'audit rotation', retainDays: 'audit retention', arguments: 'audit arguments', logLevel: 'log level',
  telemetry: 'telemetry', exporter: 'exporter', serviceName: 'service name', traces: 'signals', metrics: 'signals',
}

/**
 * Observability: what the delivered server records, so operators can see what happened. Shipped defaults are
 * edited here; paths and endpoints belong to the deployment. Not the platform's own Activity.
 */
export function ObservabilityPage() {
  const { id = '' } = useParams()
  const manifest = useManifest(id)
  return (
    <>
      <PageHead title="Observability" sub="What the delivered server records: its audit log, logs, traces and metrics.">
        <BuildButton id={id} />
      </PageHead>
      <div className="body">
        <section className="col" aria-label="Settings">
          <ErrorBox error={manifest.error} />
          {manifest.isLoading && <Loading />}
          {manifest.data && <ObservabilityForm id={id} saved={current(manifest.data)} http={manifest.data.runtime.transports?.allowed.includes('http') ?? true} />}
          <SetWhenDeployed title="Where it goes" items={[
            { label: 'Audit file', key: 'audit.file.path', hint: 'Default: audit/audit.jsonl under the server\'s storage folder.' },
            { label: 'OTLP collector', key: 'telemetry.otlp.endpoint · protocol · headers', hint: 'Default: http://localhost:4317 over gRPC. Headers are secrets.' },
            { label: 'Management port', key: 'server.management.port', hint: 'Health and metrics answer only there when it is set.' },
          ]} />
          <div className="card">
            <h2>Health checks</h2>
            <p className="hint">Built in, over HTTP; nothing to configure.</p>
            <dl className="kv">
              <dt><span className="mono">GET /health/live</span></dt><dd>Up while the process runs</dd>
              <dt><span className="mono">GET /health/ready</span></dt><dd>Ready when the configuration is loaded and a target server is reachable</dd>
              <dt><span className="mono">GET /metrics</span></dt><dd>For Prometheus, when that exporter is chosen</dd>
            </dl>
          </div>
          <p className="hint">Who changed this server on the platform is in <Link to={`/servers/${id}/activity`}>Activity</Link>.</p>
        </section>
        <section className="stage" aria-label="Customer preview">
          {manifest.data && <CustomerPreview id={id} />}
        </section>
      </div>
    </>
  )
}

function ObservabilityForm({ id, saved, http }: { id: string; saved: Settings; http: boolean }) {
  const { editable, save } = useDefinitionSave(id, 'Observability')
  const [s, setS] = useState(saved)
  useEffect(() => setS(saved), [JSON.stringify(saved)]) // eslint-disable-line react-hooks/exhaustive-deps
  const set = <K extends keyof Settings>(key: K, value: Settings[K]) => setS((old) => ({ ...old, [key]: value }))
  const off = !editable

  const changed = (Object.keys(saved) as (keyof Settings)[]).filter((k) => s[k] !== saved[k])
  const changes: Change[] = changed.flatMap((k): Change[] => PATHS[k] ? [{ op: 'set', path: PATHS[k]!, value: s[k] }] : [])
  if (changed.includes('serviceName')) {
    changes.push(s.serviceName.trim()
      ? { op: 'set', path: '/runtimeDefaults/telemetry/serviceName', value: s.serviceName.trim() }
      : { op: 'remove', path: '/runtimeDefaults/telemetry/serviceName' })
  }
  if (changed.includes('traces') || changed.includes('metrics')) {
    changes.push({ op: 'set', path: '/runtimeDefaults/telemetry/signals', value: [...(s.traces ? ['traces'] : []), ...(s.metrics ? ['metrics'] : [])] })
  }
  const sizeMb = s.rotate.startsWith('size:') ? Number(s.rotate.slice(5)) : NaN
  const problem = !Number.isInteger(s.retainDays) || s.retainDays < 1 ? 'Keep the audit log at least 1 day'
    : s.rotate.startsWith('size:') && (!Number.isInteger(sizeMb) || sizeMb < 1) ? 'Rotate at a whole number of MB, at least 1'
      : s.telemetry && !s.traces && !s.metrics ? 'Send traces, metrics or both'
        : s.telemetry && s.exporter === 'prometheus' && !http ? 'Prometheus needs the HTTP transport; use OTLP for stdio servers' : null

  return (
    <form className="list" style={{ gap: 16 }} onSubmit={(e) => { e.preventDefault(); save.mutate({ changes, what: [...new Set(changed.map((k) => LABELS[k]))].join(', ') }) }}>
      <div className="card">
        <h2>Audit log</h2>
        <Toggle label="Write an audit file" checked={s.auditEnabled} disabled={off} onChange={(v) => set('auditEnabled', v)}
          hint="One JSON line per tool call, resource read, confirmation, refused sign-in, session and configuration change. Credentials are always masked." />
        <div className="list" style={{ gap: 2, marginLeft: 24 }}>
          <label className="field"><span>Start a new file</span>
            <select className="input" value={s.rotate.startsWith('size:') ? 'size' : s.rotate} disabled={off || !s.auditEnabled} style={{ maxWidth: 240 }}
              onChange={(e) => set('rotate', e.target.value === 'size' ? 'size:100' : e.target.value)}>
              <option value="daily">Every day</option>
              <option value="hourly">Every hour</option>
              <option value="size">At a size</option>
            </select>
          </label>
        </div>
        {s.rotate.startsWith('size:') && (
          <NumberField label="Size (MB)" value={sizeMb} disabled={off || !s.auditEnabled} onChange={(v) => set('rotate', 'size:' + (Number.isNaN(v) ? '' : v))} />
        )}
        <NumberField label="Keep for (days)" value={s.retainDays} disabled={off || !s.auditEnabled} onChange={(v) => set('retainDays', v)} />
        <div className="list" style={{ gap: 2, marginLeft: 24 }}>
          <label className="field"><span>Tool arguments in the log</span>
            <select className="input" value={s.arguments} disabled={off} style={{ maxWidth: 240 }} onChange={(e) => set('arguments', e.target.value)}>
              <option value="redacted">Redacted (secrets masked)</option>
              <option value="hashed">Hashed (match, don't read)</option>
              <option value="none">Left out</option>
            </select>
          </label>
        </div>
      </div>

      <div className="card">
        <h2>Logs</h2>
        <div className="list" style={{ gap: 2 }}>
          <label className="field"><span>Level</span>
            <select className="input" value={s.logLevel} disabled={off} style={{ maxWidth: 240 }} onChange={(e) => set('logLevel', e.target.value)}>
              <option value="error">Errors only</option>
              <option value="warn">Warnings</option>
              <option value="info">Info</option>
              <option value="debug">Debug</option>
            </select>
          </label>
        </div>
      </div>

      <div className="card">
        <h2>Traces and metrics</h2>
        <Toggle label="Send OpenTelemetry traces and metrics" checked={s.telemetry} disabled={off} onChange={(v) => set('telemetry', v)}
          hint="Off unless turned on. Spans and metrics never include tool arguments." />
        <div className="list" style={{ gap: 2, marginLeft: 24 }}>
          <label className="field"><span>Exporter</span>
            <select className="input" value={s.exporter} disabled={off || !s.telemetry} style={{ maxWidth: 240 }} onChange={(e) => set('exporter', e.target.value)}>
              <option value="otlp">OTLP (push to a collector)</option>
              <option value="prometheus">Prometheus (scrape /metrics; HTTP only)</option>
            </select>
          </label>
          <label className="field"><span>Service name</span>
            <input className="input mono" value={s.serviceName} disabled={off || !s.telemetry} placeholder="the server's name" style={{ maxWidth: 240 }}
              onChange={(e) => set('serviceName', e.target.value)} />
          </label>
        </div>
        <div style={{ marginLeft: 24 }} className="list">
          <Toggle label="Traces" checked={s.traces} disabled={off || !s.telemetry} onChange={(v) => set('traces', v)} />
          <Toggle label="Metrics" checked={s.metrics} disabled={off || !s.telemetry} onChange={(v) => set('metrics', v)} />
        </div>
      </div>

      {editable && (
        <div className="actions">
          <button type="submit" className="btn btn-primary" disabled={changes.length === 0 || !!problem || save.isPending}><Icon name="save" />Save as the next version</button>
          {changes.length > 0 && <button type="button" className="btn" onClick={() => setS(saved)}><Icon name="undo" />Undo changes</button>}
          {problem && <span className="hint" role="alert">{problem}</span>}
        </div>
      )}
      <ErrorBox error={save.error} />
      <p className="hint">Defaults the package ships with; whoever runs the server can change them.</p>
    </form>
  )
}
