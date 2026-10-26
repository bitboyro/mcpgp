import { sample, typeOf } from "./sample.js";
import { codeOf, structured, type ToolResult } from "./results.js";
import type { Running } from "./server.js";

/** A tool as `tools/list` gives it. */
export interface Tool {
  name: string;
  inputSchema: any;
}

function resolver(root: any) {
  return (ref: string) => {
    if (!ref.startsWith("#/")) throw new Error(`unsupported $ref in an input schema: ${ref}`);
    return ref.slice(2).split("/").reduce((node, key) => node?.[key], root);
  };
}

/** Arguments that satisfy the tool's input schema: only what is required, or every optional field too. */
export function validArguments(tool: Tool, variant: "required" | "all", seed = 0): Record<string, unknown> {
  return sample(tool.inputSchema, { optional: variant === "all" ? "all" : "none", branch: seed, resolve: resolver(tool.inputSchema) });
}

export interface Mutation {
  name: string;
  args: Record<string, unknown>;
}

function wrongType(schema: any): unknown {
  switch (typeOf(schema)) {
    case "string":
      return 12345;
    case "integer":
    case "number":
      return "not-a-number";
    case "boolean":
      return "yes";
    case "array":
      return "not-a-list";
    case "object":
      return "not-an-object";
    default:
      return { unexpected: true };
  }
}

/**
 * Arguments that break the input schema in exactly one way each: a required field missing, a wrong
 * type, a value outside an enum, a bound or a pattern, or a field the schema doesn't have.
 */
export function mutations(tool: Tool, valid: Record<string, unknown>, limit = 12): Mutation[] {
  const resolve = resolver(tool.inputSchema);
  const deref = (s: any) => (s?.$ref ? resolve(s.$ref) : s);
  const out: Mutation[] = [];
  const root = tool.inputSchema;
  const copy = () => structuredClone(valid);

  for (const key of root.required ?? []) {
    const args = copy();
    delete args[key];
    out.push({ name: `without required ${key}`, args });
  }
  if (root.additionalProperties === false) out.push({ name: "with an argument the tool doesn't have", args: { ...copy(), unexpected: 1 } });

  for (const [group, value] of Object.entries(valid)) {
    const schema = deref(root.properties?.[group]);
    if (!schema || typeOf(schema) !== "object" || !value || typeof value !== "object" || Array.isArray(value) || schema.oneOf) continue;
    for (const name of schema.required ?? []) {
      const args = copy();
      delete (args[group] as any)[name];
      out.push({ name: `without required ${group}.${name}`, args });
    }
    for (const [name, raw] of Object.entries<any>(schema.properties ?? {})) {
      if (!(name in (value as any))) continue;
      const prop = deref(raw);
      if (prop.oneOf || prop.anyOf) continue;
      const set = (v: unknown, why: string) => {
        const args = copy();
        (args[group] as any)[name] = v;
        out.push({ name: `${group}.${name} ${why}`, args });
      };
      set(wrongType(prop), "of the wrong type");
      if (Array.isArray(prop.enum)) set("not-an-option", "outside its enum");
      if (typeof prop.maxLength === "number") set("x".repeat(prop.maxLength + 1), `longer than ${prop.maxLength}`);
      if (typeof prop.pattern === "string" && !new RegExp(prop.pattern, "u").test("!!")) set("!!", `not matching ${prop.pattern}`);
      if (typeof prop.minimum === "number") set(prop.minimum - 1, `below ${prop.minimum}`);
      if (typeof prop.maximum === "number") set(prop.maximum + 1, `above ${prop.maximum}`);
    }
    if (schema.additionalProperties === false) {
      const args = copy();
      (args[group] as any).unexpected = 1;
      out.push({ name: `${group} with a field it doesn't have`, args });
    }
  }
  return out.slice(0, limit);
}

/** Calls a tool; a destructive tool's CONFIRMATION_REQUIRED is answered with its token, as an agent would after the user agreed. */
export async function callConfirmed(server: Running, name: string, args: Record<string, unknown>): Promise<ToolResult> {
  let result = (await server.client.callTool({ name, arguments: args })) as ToolResult;
  if (result.isError && codeOf(result) === "CONFIRMATION_REQUIRED") {
    const token = structured(result)?.confirm;
    if (typeof token === "string") result = (await server.client.callTool({ name, arguments: { ...args, confirm: token } })) as ToolResult;
  }
  return result;
}

/** The code a documented upstream status must become (audit-and-errors.md §3). */
export function expectedCode(status: string, documented: Set<string>): string[] | null {
  if (!/^\d{3}$/.test(status)) return null;
  const n = Number(status);
  if (n >= 200 && n < 300) return [];
  if (n === 401 || n === 403) return ["AUTH_UPSTREAM"];
  if (n === 429) return documented.has("RATE_LIMITED") ? ["UPSTREAM_4XX", "RATE_LIMITED"] : ["UPSTREAM_4XX"];
  if (n >= 400 && n < 500) return ["UPSTREAM_4XX"];
  if (n >= 500) return ["UPSTREAM_5XX"];
  return null;
}
