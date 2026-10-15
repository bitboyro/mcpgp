import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { FIXTURES } from "./paths.js";
import { CLAIMED_REVISIONS } from "./server.js";

/**
 * Golden surfaces (release-gate.md §6, S2): what a server lists (tools, resources, prompts) as
 * reviewed JSON in conformance/fixtures/golden/. A change to a name, a description or a schema shows up
 * as a diff in the PR and needs a review. GATE_UPDATE_GOLDEN=1 rewrites them.
 */
export const UPDATE = process.env.GATE_UPDATE_GOLDEN === "1";

export function goldenFile(id: string): string {
  return join(FIXTURES, "golden", `${id.replace(/\//g, "__")}.surface.json`);
}

/** Keys sorted at every level, so the file is stable and diffs are small. */
export function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, canonical((value as any)[k])]));
  }
  return value;
}

export interface Surface {
  tools: any[];
  resources?: any[];
  resourceTemplates?: any[];
  prompts?: any[];
}

export function normalize(surface: Surface): Surface {
  const byName = (a: any, b: any) => String(a.name ?? a.uri ?? a.uriTemplate).localeCompare(String(b.name ?? b.uri ?? b.uriTemplate));
  const out: Surface = { tools: [...surface.tools].sort(byName) };
  if (surface.resources) out.resources = [...surface.resources].sort(byName);
  if (surface.resourceTemplates) out.resourceTemplates = [...surface.resourceTemplates].sort(byName);
  if (surface.prompts) out.prompts = [...surface.prompts].sort(byName);
  return canonical(out) as Surface;
}

/**
 * The golden is recorded on the newest revision; an older one lacks what it can't carry
 * (compatibility.md §1.2): titles and output schemas before 2025-06-18, icons before 2025-11-25.
 */
export function forRevision(surface: Surface, revision: string): Surface {
  const newest = CLAIMED_REVISIONS.at(-1)!;
  if (revision === newest) return surface;
  const drop = revision < "2025-06-18" ? ["title", "outputSchema", "icons"] : ["icons"];
  const strip = (items?: any[]) => items?.map((item) => Object.fromEntries(Object.entries(item).filter(([k]) => !drop.includes(k))));
  return { ...surface, tools: strip(surface.tools)!, resources: strip(surface.resources), resourceTemplates: strip(surface.resourceTemplates), prompts: strip(surface.prompts) };
}

export function readGolden(id: string): Surface | undefined {
  const file = goldenFile(id);
  return existsSync(file) ? (JSON.parse(readFileSync(file, "utf8")) as Surface) : undefined;
}

export function writeGolden(id: string, surface: Surface): void {
  const file = goldenFile(id);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, JSON.stringify(normalize(surface), null, 2) + "\n");
}

/** What differs between two surfaces, tool by tool and field by field (empty when equal). */
export function differences(want: Surface, got: Surface): string[] {
  const out: string[] = [];
  const index = (items: any[] = []) => new Map(items.map((i) => [String(i.name ?? i.uri ?? i.uriTemplate), i]));
  for (const kind of ["tools", "resources", "resourceTemplates", "prompts"] as const) {
    const w = index(want[kind]);
    const g = index(got[kind]);
    for (const name of w.keys()) if (!g.has(name)) out.push(`${kind}: ${name} is gone`);
    for (const name of g.keys()) if (!w.has(name)) out.push(`${kind}: ${name} is new`);
    for (const [name, item] of w) {
      const other = g.get(name);
      if (!other) continue;
      for (const key of new Set([...Object.keys(item), ...Object.keys(other)])) {
        const a = JSON.stringify(canonical(item[key]));
        const b = JSON.stringify(canonical(other[key]));
        if (a !== b) out.push(`${kind}: ${name}.${key} changed\n    was: ${(a ?? "(none)").slice(0, 300)}\n    now: ${(b ?? "(none)").slice(0, 300)}`);
      }
    }
  }
  return out;
}
