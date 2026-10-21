import { expect } from "vitest";
import type { Mock, Received, Reply } from "./mock.js";
import { codeOf, documentedCodes, structured, text, type ToolResult } from "./results.js";
import type { Running } from "./server.js";
import { parseMultipart } from "./validate.js";

/**
 * One step of a traffic file (conformance.md §2, "traffic v2" in release-gate.md §4): an MCP request,
 * what the API must receive, and what the client must get. Both forms are read:
 *   v1: { mcp: { method, params }, upstream, respond, expect }
 *   v2: { call, params, mock: { respond }, upstream, expect }
 */
export interface Step {
  name: string;
  mcp?: { method: string; params?: any };
  call?: string;
  params?: any;
  respond?: any;
  mock?: { respond?: any };
  upstream?: "none" | Record<string, any> | Array<Record<string, any>>;
  expect?: Record<string, any>;
}

const CODES = documentedCodes();

/** Matchers (conformance.md §2): exact, { startsWith }, { matches }, { absent: true }, { any: true }. */
export function matches(actual: unknown, want: any, where: string, problems: string[]): void {
  if (want && typeof want === "object" && !Array.isArray(want)) {
    if ("absent" in want) {
      if (actual !== undefined && actual !== null) problems.push(`${where}: expected absent, got ${JSON.stringify(actual)}`);
      return;
    }
    if ("any" in want) {
      if (actual === undefined) problems.push(`${where}: expected a value, got none`);
      return;
    }
    if ("startsWith" in want) {
      if (typeof actual !== "string" || !actual.startsWith(want.startsWith)) problems.push(`${where}: expected to start with ${JSON.stringify(want.startsWith)}, got ${JSON.stringify(actual)}`);
      return;
    }
    if ("matches" in want) {
      if (typeof actual !== "string" || !new RegExp(want.matches).test(actual)) problems.push(`${where}: expected to match /${want.matches}/, got ${JSON.stringify(actual)}`);
      return;
    }
    // a subset: every key in `want` must match; extra keys in `actual` are fine
    if (!actual || typeof actual !== "object") {
      problems.push(`${where}: expected an object, got ${JSON.stringify(actual)}`);
      return;
    }
    for (const [k, v] of Object.entries(want)) matches((actual as any)[k], v, `${where}.${k}`, problems);
    return;
  }
  if (Array.isArray(want)) {
    if (!Array.isArray(actual) || actual.length !== want.length) {
      problems.push(`${where}: expected ${JSON.stringify(want)}, got ${JSON.stringify(actual)}`);
      return;
    }
    want.forEach((w, i) => matches(actual[i], w, `${where}[${i}]`, problems));
    return;
  }
  const same = actual === want || (actual !== undefined && actual !== null && String(actual) === String(want));
  if (!same) problems.push(`${where}: expected ${JSON.stringify(want)}, got ${JSON.stringify(actual)}`);
}

function reply(respond: any): Reply {
  if (!respond) return {};
  return { status: respond.status, headers: respond.headers, json: respond.json ?? respond.problem, body: respond.body, fault: respond.fault, delayMs: respond.delayMs };
}

function checkRequest(want: Record<string, any>, got: Received, where: string, problems: string[]) {
  if (want.method) matches(got.method, want.method, `${where} method`, problems);
  if (want.path) matches(got.path, want.path, `${where} path`, problems);
  const query = new URLSearchParams(got.query);
  for (const [k, v] of Object.entries<any>(want.query ?? {})) matches(query.get(k) ?? undefined, v, `${where} query ${k}`, problems);
  for (const [k, v] of Object.entries<any>(want.headers ?? {})) matches(got.headers[k.toLowerCase()], v, `${where} header ${k}`, problems);
  if (want.json !== undefined) {
    let body: unknown;
    try {
      body = JSON.parse(got.body.toString("utf8"));
    } catch {
      problems.push(`${where}: body is not JSON`);
    }
    if (body !== undefined) matches(body, want.json, `${where} body`, problems);
  }
  if (want.parts) {
    const parts = parseMultipart(got.headers["content-type"] ?? "", got.body) ?? [];
    if (parts.length !== want.parts.length) problems.push(`${where}: ${parts.length} parts, expected ${want.parts.length}`);
    want.parts.forEach((w: any, i: number) => {
      const p = parts[i];
      if (!p) return;
      matches(p.name, w.name, `${where} part ${i} name`, problems);
      if (w.contentType) matches(p.contentType, w.contentType, `${where} part ${w.name} Content-Type`, problems);
      if (w.filename) matches(p.filename, w.filename, `${where} part ${w.name} filename`, problems);
      if (w.json !== undefined) matches(JSON.parse(p.body.toString("utf8")), w.json, `${where} part ${w.name} JSON`, problems);
      if (w.sizeBytes !== undefined) matches(p.body.length, w.sizeBytes, `${where} part ${w.name} size`, problems);
    });
  }
}

/** Checks a tool result against `expect` and the documented error codes. */
export function checkResult(result: ToolResult, want: Record<string, any> | undefined, problems: string[]): void {
  const code = codeOf(result);
  if (result.isError && code && !CODES.has(code)) problems.push(`error code ${code} is not in audit-and-errors.md §3`);
  if (!want) return;
  if ("isError" in want && !!result.isError !== want.isError) problems.push(`isError: expected ${want.isError}, got ${!!result.isError} (${text(result).slice(0, 300)})`);
  if (want.code !== undefined) matches(code, want.code, "code", problems);
  if (want.structuredContent !== undefined) matches(structured(result), want.structuredContent, "structuredContent", problems);
  if (want.meta !== undefined) matches(result._meta, want.meta, "_meta", problems);
}

/** Runs one step: sets the mock's reply, sends the MCP request, and checks the API's side and the client's side. */
export async function runStep(server: Running, mock: Mock, step: Step): Promise<void> {
  const method = step.call ?? step.mcp?.method ?? "tools/call";
  const params = step.params ?? step.mcp?.params ?? {};
  const respond = step.mock?.respond ?? step.respond;
  if (respond) mock.next(reply(respond));
  const before = mock.journal.length;
  const problems: string[] = [];

  if (method === "tools/call") {
    const result = (await server.client.callTool({ name: params.name, arguments: params.arguments ?? {} })) as ToolResult;
    checkResult(result, step.expect, problems);
  } else if (method === "tools/list") {
    const result = await server.client.listTools(params);
    if (step.expect) matches(result, step.expect, "tools/list", problems);
  } else {
    throw new Error(`step method ${method} is not supported by the gate yet`);
  }

  const received = mock.journal.slice(before);
  for (const r of received) for (const v of r.violations) problems.push(`API request ${r.method} ${r.path}: ${v}`);
  if (step.upstream === "none") {
    if (received.length) problems.push(`expected no API request, got ${received.map((r) => `${r.method} ${r.path}`).join(", ")}`);
  } else if (step.upstream) {
    const wanted = Array.isArray(step.upstream) ? step.upstream : [step.upstream];
    if (received.length !== wanted.length) {
      problems.push(`expected ${wanted.length} API request(s), got ${received.length}: ${received.map((r) => `${r.method} ${r.path}`).join(", ")}`);
    }
    wanted.forEach((w, i) => received[i] && checkRequest(w, received[i], `API request ${i + 1}`, problems));
  }
  if (server.transportErrors.length) problems.push(`transport errors: ${server.transportErrors.join("; ")}`);
  expect(problems, problems.join("\n")).toEqual([]);
}
