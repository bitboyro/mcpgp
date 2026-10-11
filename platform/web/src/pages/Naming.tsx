import { useQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { nameRows, policyLines, unusedLockEntries, vocabulary, type NameRow } from '../naming'
import { useManifest } from '../serverData'
import { ErrorBox, Loading, Segmented } from '../ui'

type Show = 'all' | 'drift' | 'unlocked'

const HOW: Record<NameRow['how'], string> = {
  pipeline: 'from the policy',
  rule: 'set by a rule',
  lock: 'kept by the lock file',
  drift: 'kept by the lock file; the policy now gives another name',
}

/**
 * The naming of a server (naming.md): the policy it follows, in words, its vocabulary, and every tool's
 * name with how it came about, including names the lock file keeps although the policy would change them.
 */
export function NamingView({ id }: { id: string }) {
  const manifest = useManifest(id)
  const naming = useQuery({ queryKey: ['naming', id], queryFn: () => api.naming(id), retry: false })
  const [show, setShow] = useState<Show>('all')
  const rows = useMemo(() => (manifest.data ? nameRows(manifest.data) : []), [manifest.data])
  const drift = rows.filter((r) => r.how === 'drift')
  const unlocked = rows.filter((r) => r.how === 'pipeline' || r.how === 'rule')
  const shown = show === 'drift' ? drift : show === 'unlocked' ? unlocked : rows
  const unused = naming.data ? unusedLockEntries(naming.data, rows) : []

  return (
    <div className="body">
      <section className="col" aria-label="Naming policy">
        <ErrorBox error={naming.error ?? manifest.error} />
        {naming.isLoading && <Loading what="the naming policy" />}
        {naming.data && (
          <>
            <div className="card">
              <div className="card-head">
                <h2>Policy</h2>
                {naming.data.extends ? <span className="mono hint">{naming.data.extends}</span> : <span className="hint">the server's own</span>}
              </div>
              {naming.data.extends && (
                <p className="hint">Shared from the <Link to="/library">library</Link>; lines marked <span className="tag">this server</span> are
                  set in the server's definition on top of it.</p>
              )}
              <dl className="kv">
                {policyLines(naming.data).map((l) => (
                  <div key={l.label} style={{ display: 'contents' }}>
                    <dt>{l.label}</dt>
                    <dd>{l.value}{l.own && naming.data.extends && <> <span className="tag">this server</span></>}</dd>
                  </div>
                ))}
              </dl>
            </div>
            <div className="card">
              <h2>Vocabulary</h2>
              <p className="hint">Words in names that become other words, after the name is shaped.</p>
              {vocabulary(naming.data).length === 0 ? <p className="empty">No vocabulary.</p> : (
                <table className="table" aria-label="Vocabulary">
                  <thead><tr><th>Words</th><th>Become</th><th /></tr></thead>
                  <tbody>
                    {vocabulary(naming.data).map((v) => (
                      <tr key={v.words}><td className="mono">{v.words}</td><td className="mono">{v.becomes}</td>
                        <td>{v.own && naming.data.extends && <span className="tag">this server</span>}</td></tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <p className="hint">Change the policy in the definition's files (<span className="mono">naming</span>), on{' '}
              <Link to={`/servers/${id}/sources`}>Sources</Link>; shared policies change in the library.</p>
          </>
        )}
      </section>
      <section className="stage" aria-label="Names">
        <div className="card-head">
          <div>
            <div className="eyebrow">Names</div>
            <h2>{rows.length} tool names{drift.length > 0 ? `, ${drift.length} kept from the lock file` : ''}</h2>
          </div>
          <Segmented role="tab" label="Show names" value={show} onChange={setShow}
            options={[['all', 'All'], ['drift', `Would change (${drift.length})`], ['unlocked', `Not locked (${unlocked.length})`]]} />
        </div>
        {manifest.isLoading && <Loading what="names" />}
        {naming.data && !naming.data.lock.present && (
          <div className="note note-warn">No <span className="mono">mcp-names.lock</span> yet: names may change when the policy, the
            rules or the contract change. Lock them with <span className="mono">mcpgp names accept --all</span> and commit the file
            with the definition.</div>
        )}
        {drift.length > 0 && show !== 'unlocked' && (
          <div className="note">The lock file keeps these names so agents and prompts that use them keep working. To take the new
            names, accept them with <span className="mono">mcpgp names accept</span>, which is a breaking change for clients.</div>
        )}
        <table className="table table-stage" aria-label="Tool names">
          <thead><tr><th>Operation</th><th>Name</th><th>How</th></tr></thead>
          <tbody>
            {shown.map((r) => (
              <tr key={r.name}>
                <td className="mono">{r.id.replace(/^[^:]+:/, '').replace(':', ' ')}</td>
                <td className="mono">{r.name}{r.how === 'drift' && <><br /><span className="meta">now: {r.computed}</span></>}</td>
                <td>{HOW[r.how]}{r.since && <span className="meta"> · since {r.since}</span>}
                  {r.rule && <><br /><span className="meta mono">{r.rule}</span></>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {shown.length === 0 && <p className="empty">No names here.</p>}
        {unused.length > 0 && show === 'all' && (
          <>
            <h3>In the lock file, not in this build</h3>
            <p className="hint">The operation is gone or left out; if it comes back, it gets its old name again.</p>
            <ul className="mono" style={{ fontSize: 13, margin: 0, paddingLeft: 18 }}>
              {unused.map((e) => <li key={e.id}>{e.name} <span className="meta">({e.id.replace(/^[^:]+:/, '').replace(':', ' ')})</span></li>)}
            </ul>
          </>
        )}
      </section>
    </div>
  )
}
