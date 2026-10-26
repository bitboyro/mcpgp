import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, type VersionChange } from '../api'
import { useServer } from '../serverData'
import { ErrorBox, Loading, PageHead } from '../ui'

const GROUPS: [VersionChange['impact'], string, string][] = [
  ['breaking', 'Breaking', 'Agents or clients of the earlier version need updating: tools removed or renamed, inputs removed or now required, types changed.'],
  ['notable', 'Worth a look', 'Behaviour that changes without breaking calls: annotations, confirmations, exposure, the default API version.'],
  ['info', 'Other changes', 'New tools, new optional inputs, new API versions, texts.'],
]

/**
 * What changed between two versions of a server, as `mcpgp diff` reports it: by default since the last
 * released build (or the version before), to the latest. The release notes for customers, and the
 * review before releasing.
 */
export function ChangesPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const server = useServer(id)
  const from = params.get('from') ?? undefined
  const to = params.get('to') ?? undefined
  const diff = useQuery({ queryKey: ['diff', id, from, to], queryFn: () => api.diff(id, from, to), enabled: !!server.data?.latestVersion, retry: false })
  const [copied, setCopied] = useState(false)
  const versions = [...(server.data?.versions ?? [])].reverse()
  const pick = (key: 'from' | 'to', value: string) => {
    const next = new URLSearchParams(params)
    next.set(key, value)
    setParams(next, { replace: true })
    setCopied(false)
  }
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(diff.data!.markdown)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }
  return (
    <>
      <PageHead title="Changes" sub="What changed between two versions: for the release notes, and before you release." />
      <div className="body">
        <section className="col col-wide" aria-label="Changes">
          <ErrorBox error={server.error ?? diff.error} />
          {(server.isLoading || diff.isLoading) && <Loading what="the changes" />}
          {server.data && (
            <div className="card">
              <div className="filters">
                <label className="field">From
                  <select className="input" value={from ?? (diff.data?.from.released ? 'released' : String(diff.data?.from.version ?? ''))}
                    onChange={(e) => pick('from', e.target.value)}>
                    {diff.data?.released != null && <option value="released">The last release (revision {diff.data.released})</option>}
                    {versions.map((v) => <option key={v.number} value={String(v.number)}>Revision {v.number}{v.message ? ` · ${v.message}` : ''}</option>)}
                  </select>
                </label>
                <label className="field">To
                  <select className="input" value={to ?? String(diff.data?.to.version ?? '')} onChange={(e) => pick('to', e.target.value)}>
                    {versions.map((v) => <option key={v.number} value={String(v.number)}>Revision {v.number}{v.number === server.data!.latestVersion ? ' (latest)' : ''}</option>)}
                  </select>
                </label>
              </div>
            </div>
          )}
          {diff.data && (
            <div className="card">
              <div className="card-head">
                <div>
                  <div className="eyebrow">Revision {diff.data.from.version} ({diff.data.from.serverVersion || '—'}) → {diff.data.to.version} ({diff.data.to.serverVersion})</div>
                  <h2>{diff.data.summary}</h2>
                </div>
                {diff.data.changes.length > 0 && (
                  <button type="button" className="btn btn-sm" onClick={copy}>{copied ? 'Copied' : 'Copy as Markdown'}</button>
                )}
              </div>
              {diff.data.changes.length === 0 && <p className="hint">The tools agents see and what customers configure are the same in both versions.</p>}
              {GROUPS.map(([impact, title, hint]) => {
                const list = diff.data!.changes.filter((c) => c.impact === impact)
                if (list.length === 0) return null
                return (
                  <div key={impact} className="list" style={{ gap: 6, marginTop: 10 }}>
                    <h3 className={impact === 'breaking' ? 'chip chip-bad' : impact === 'notable' ? 'chip chip-warn' : 'chip chip-idle'}
                      style={{ alignSelf: 'flex-start' }}>{title} · {list.length}</h3>
                    <span className="hint">{hint}</span>
                    <ul className="list" style={{ listStyle: 'none', margin: 0, padding: 0, gap: 4 }}>
                      {list.map((c, i) => (
                        <li key={i} className="row" style={{ justifyContent: 'flex-start', gap: 8 }}>
                          {c.tool ? <Link className="mono" to={`/servers/${id}/tools?tool=${encodeURIComponent(c.tool)}`}>{c.tool}</Link>
                            : <span className="tag">server</span>}
                          <span style={{ fontSize: 14 }}>{c.text}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                )
              })}
            </div>
          )}
        </section>
      </div>
    </>
  )
}
