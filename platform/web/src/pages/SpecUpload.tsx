import { useRef, useState } from 'react'
import type { SpecProblems } from '../api'
import { readPicked } from '../files'
import { mergeFiles, specOnly } from '../specUpload'

export type SpecUpload = Record<string, string> | Blob

/**
 * Picking an API's spec: one file, several files, a folder, or a zip, by button or by dropping them.
 * Picking again adds to the files already picked (a zip replaces them), so files the platform says are
 * missing can be added without starting over.
 */
export function SpecPicker({ files, onFiles }: { files: SpecUpload | null; onFiles: (files: SpecUpload) => void }) {
  const single = useRef<HTMLInputElement>(null)
  const folder = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  const pick = async (list: FileList | File[] | null) => {
    if (!list || list.length === 0) return
    const picked = await readPicked(list)
    onFiles(mergeFiles(files, picked.files instanceof Blob ? picked.files : specOnly(picked.files)))
  }
  const names = files === null ? [] : files instanceof Blob ? ['a zip'] : Object.keys(files).sort()
  return (
    <div className="list" style={{ gap: 10 }}>
      <div role="button" tabIndex={0} aria-label="Drop the spec's files here, or choose them"
        onClick={() => single.current?.click()} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') single.current?.click() }}
        onDragOver={(e) => { e.preventDefault(); setOver(true) }} onDragLeave={() => setOver(false)}
        onDrop={(e) => { e.preventDefault(); setOver(false); pick(Array.from(e.dataTransfer.files)) }}
        style={{ border: '1px dashed var(--control-line)', borderRadius: 12, padding: '18px 14px', textAlign: 'center', cursor: 'pointer',
          background: over ? 'var(--accent-soft)' : undefined }}>
        <b>{files === null ? 'Drop your OpenAPI or Swagger file here' : 'Drop more files here'}</b><br />
        <span className="hint">JSON or YAML: Swagger 2.0, OpenAPI 3.0 or 3.1. When it refers to other files ($ref), add them too, or a zip of the folder.</span>
      </div>
      <div className="actions">
        <button type="button" className="btn btn-stage btn-sm" onClick={() => single.current?.click()}>{files === null ? 'Choose files' : 'Add files'}</button>
        <button type="button" className="btn btn-stage btn-sm" onClick={() => folder.current?.click()}>Choose a folder</button>
        <input ref={single} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true" multiple accept=".json,.yaml,.yml,.zip,application/zip"
          onChange={(e) => { pick(e.target.files); e.target.value = '' }} />
        <input ref={folder} type="file" className="visually-hidden" tabIndex={-1} aria-hidden="true" multiple {...{ webkitdirectory: '', directory: '' }}
          onChange={(e) => { pick(e.target.files); e.target.value = '' }} />
      </div>
      {names.length > 0 && (
        <p className="hint" style={{ overflowWrap: 'anywhere' }}>Picked: <span className="mono">{names.slice(0, 8).join(', ')}{names.length > 8 ? ` and ${names.length - 8} more` : ''}</span></p>
      )}
    </div>
  )
}

/** What a SPEC_PROBLEMS answer says, in a form a person can act on: files to add, the main file to choose. */
export function SpecProblemsView({ problems, main, onMain }: { problems: SpecProblems; main: string; onMain: (main: string) => void }) {
  return (
    <div className="note note-bad list" style={{ gap: 8 }} role="alert">
      {(problems.candidates ?? []).length > 0 && (
        <label className="field">Several files could be the API's spec. Which one is it?
          <select className="input" value={main} onChange={(e) => onMain(e.target.value)}>
            <option value="">Choose…</option>
            {problems.candidates!.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </label>
      )}
      {(problems.missing ?? []).length > 0 && (
        <div>
          <b>Files the spec refers to but that weren't uploaded: add them with Add files.</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {problems.missing!.map((m) => (
              <li key={m.file}><span className="mono">{m.file}</span> <span className="hint">(from {m.from}, at <span className="mono">{m.pointer}</span>)</span></li>
            ))}
          </ul>
        </div>
      )}
      {(problems.broken ?? []).length > 0 && (
        <div>
          <b>References to parts that aren't there: fix them in the spec, then pick the files again.</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {problems.broken!.map((b) => (
              <li key={b.file + b.pointer}><span className="mono">{b.file}</span> <span className="hint">(from {b.from}, at <span className="mono">{b.pointer}</span>)</span></li>
            ))}
          </ul>
        </div>
      )}
      {(problems.remote ?? []).length > 0 && (
        <div>
          <b>References to URLs: the platform doesn't fetch them.</b> Download those files, add them, and change the references to their paths.
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {problems.remote!.map((r) => <li key={r.file + r.pointer}><span className="mono">{r.file}</span> <span className="hint">(from {r.from})</span></li>)}
          </ul>
        </div>
      )}
      {(problems.problems ?? []).filter((p) => !/is missing \(referenced|doesn't fetch URLs|several files could be|which isn't there$/.test(p)).map((p) => <div key={p}>{p}</div>)}
    </div>
  )
}
