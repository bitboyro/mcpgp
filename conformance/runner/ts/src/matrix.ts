import { formats, supports, type Format } from "./formats.js";
import type { Package } from "./packages.js";
import { revisions, transports, type TransportKind } from "./server.js";

export interface Combination {
  format: Format;
  transport: TransportKind;
  revision: string;
  /** "mcpb · stdio · 2025-11-25": the report reads suites and combinations from describe names. */
  label: string;
}

/** Every format the package ships × the transports that format runs on × the revisions under test. */
export function combinations(pkg: Package, only?: string[]): Combination[] {
  const out: Combination[] = [];
  for (const format of formats()) {
    for (const transport of transports()) {
      if (!supports(pkg, format, transport)) continue;
      for (const revision of revisions()) {
        if (only && !only.includes(revision)) continue;
        out.push({ format, transport, revision, label: `${format} · ${transport} · ${revision}` });
      }
    }
  }
  return out;
}
