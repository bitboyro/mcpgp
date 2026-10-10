// Reading a project the person picks (a folder or a zip) into what POST /servers takes.

export interface PickedProject {
  /** The folder's files by path, without the folder's own name; or the zip as is. */
  files: Record<string, string> | Blob
  /** A suggested server id: the folder or zip name. */
  name: string
  count: number
}

export async function readPicked(list: FileList | File[]): Promise<PickedProject> {
  const files = Array.from(list)
  if (files.length === 1 && files[0].name.toLowerCase().endsWith('.zip')) {
    return { files: files[0], name: files[0].name.replace(/\.zip$/i, ''), count: 1 }
  }
  const out: Record<string, string> = {}
  let folder = ''
  for (const file of files) {
    const path = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
    const parts = path.split('/')
    if (parts.length > 1) folder = parts[0]
    const relative = parts.length > 1 ? parts.slice(1).join('/') : path
    // skip what never belongs to a project
    if (relative.split('/').some((p) => p.startsWith('.') || p === 'node_modules')) continue
    out[relative] = await file.text()
  }
  return { files: out, name: folder || 'new-server', count: Object.keys(out).length }
}
