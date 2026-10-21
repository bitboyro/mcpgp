import { readFileSync } from "node:fs";
import { join } from "node:path";
import { REPO } from "./paths.js";

/**
 * The error codes of audit-and-errors.md §3, read from the spec itself so the allowed list moves with
 * it. A result with a code that isn't there fails the gate.
 */
export function documentedCodes(): Set<string> {
  const text = readFileSync(join(REPO, "docs", "spec", "audit-and-errors.md"), "utf8");
  const section = text.split(/^## /m).find((s) => s.startsWith("3. Error codes")) ?? "";
  const codes = new Set<string>();
  for (const line of section.split("\n")) {
    if (!line.startsWith("| `")) continue;
    const first = line.split("|")[1] ?? "";
    for (const m of first.matchAll(/`([A-Z][A-Z0-9_]+)`/g)) codes.add(m[1]);
  }
  return codes;
}

export interface ToolResult {
  isError?: boolean;
  content?: Array<{ type: string; text?: string }>;
  structuredContent?: Record<string, unknown>;
  _meta?: Record<string, unknown>;
}

/**
 * The result's structured value: `structuredContent`, or, on revisions without it (2025-03-26), the
 * JSON in the text content (compatibility.md §1.2).
 */
export function structured(result: ToolResult): any {
  if (result.structuredContent) return result.structuredContent;
  for (const c of result.content ?? []) {
    if (c.type === "text" && c.text) {
      try {
        return JSON.parse(c.text);
      } catch {
        // plain text
      }
    }
  }
  return undefined;
}

export function codeOf(result: ToolResult): string | undefined {
  const value = structured(result);
  if (value && typeof value === "object" && typeof value.code === "string") return value.code;
  // an error with only text: "CODE: message" is how some errors read
  const text = result.content?.find((c) => c.type === "text")?.text ?? "";
  return /^([A-Z][A-Z0-9_]{3,})\b/.exec(text)?.[1];
}

export function text(result: ToolResult): string {
  return (result.content ?? []).map((c) => c.text ?? `[${c.type}]`).join("\n");
}
