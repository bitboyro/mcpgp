import type { Contract, Operation, Parameter } from "./spec.js";
import { typeOf } from "./sample.js";

/** A request as the mock received it. */
export interface Request {
  method: string;
  /** The raw path, still percent-encoded. */
  path: string;
  query: string;
  headers: Record<string, string>;
  body: Buffer;
}

export interface Part {
  name: string;
  filename?: string;
  contentType?: string;
  body: Buffer;
}

function mediaType(value: string | undefined): string {
  return (value ?? "").split(";")[0].trim().toLowerCase();
}

function matchesMedia(declared: string, actual: string): boolean {
  const d = mediaType(declared);
  if (d === "*/*") return true;
  if (d.endsWith("/*")) return actual.startsWith(d.slice(0, -1));
  return d === actual;
}

function resolved(contract: Contract, schema: any): any {
  let s = schema;
  for (let i = 0; s?.$ref && i < 10; i++) s = contract.resolve(s.$ref);
  return s ?? {};
}

/** A parameter's text turned into the type its schema says, so it can be checked against the schema. */
function coerce(contract: Contract, schema: any, text: string): unknown {
  const type = typeOf(resolved(contract, schema));
  if (type === "integer" || type === "number") {
    const n = Number(text);
    return text.trim() === "" || Number.isNaN(n) ? text : n;
  }
  if (type === "boolean") return text === "true" ? true : text === "false" ? false : text;
  if (type === "null") return text === "null" ? null : text;
  return text;
}

function check(contract: Contract, schema: any, value: unknown, where: string, problems: string[]) {
  if (!schema) return;
  const validate = contract.validator(schema);
  if (!validate(value)) {
    for (const e of validate.errors ?? []) problems.push(`${where}${e.instancePath} ${e.message} (got ${JSON.stringify(value)?.slice(0, 120)})`);
  }
}

function paramValue(contract: Contract, p: Parameter, values: string[]): unknown {
  const schema = resolved(contract, p.schema);
  if (typeOf(schema) === "array") {
    const explode = p.explode ?? (p.in === "query" || p.in === "cookie");
    const items = explode ? values : values.flatMap((v) => v.split(","));
    return items.map((v) => coerce(contract, schema.items ?? {}, v));
  }
  return coerce(contract, p.schema, values[0]);
}

function cookies(header: string | undefined): Map<string, string> {
  const map = new Map<string, string>();
  for (const part of (header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0) map.set(part.slice(0, i).trim(), decodeURIComponent(part.slice(i + 1).trim()));
  }
  return map;
}

export function parseMultipart(contentType: string, body: Buffer): Part[] | undefined {
  const match = /boundary="?([^";]+)"?/i.exec(contentType);
  if (!match) return undefined;
  const boundary = Buffer.from(`--${match[1]}`);
  const parts: Part[] = [];
  let start = body.indexOf(boundary);
  while (start >= 0) {
    const next = body.indexOf(boundary, start + boundary.length);
    if (next < 0) break;
    const chunk = body.subarray(start + boundary.length + 2, next - 2); // CRLF after the boundary, CRLF before the next
    const split = chunk.indexOf("\r\n\r\n");
    if (split >= 0) {
      const headers = chunk.subarray(0, split).toString("utf8");
      const disposition = /content-disposition:([^\r\n]*)/i.exec(headers)?.[1] ?? "";
      parts.push({
        name: /name="([^"]*)"/i.exec(disposition)?.[1] ?? "",
        filename: /filename="([^"]*)"/i.exec(disposition)?.[1],
        contentType: /content-type:\s*([^\r\n]*)/i.exec(headers)?.[1]?.trim(),
        body: chunk.subarray(split + 4),
      });
    }
    start = next;
  }
  return parts;
}

function credentialsSent(op: Operation, req: Request): string[] {
  const sent: string[] = [];
  if (req.headers.authorization) sent.push("Authorization");
  for (const scheme of Object.values<any>(op.contract.doc.components?.securitySchemes ?? op.contract.doc.securityDefinitions ?? {})) {
    if (scheme.type === "apiKey" && scheme.in === "header" && req.headers[scheme.name.toLowerCase()]) sent.push(scheme.name);
    if (scheme.type === "apiKey" && scheme.in === "query" && new URLSearchParams(req.query).has(scheme.name)) sent.push(scheme.name);
  }
  return sent;
}

function credentialPresent(op: Operation, req: Request): boolean {
  const schemes = op.contract.doc.components?.securitySchemes ?? op.contract.doc.securityDefinitions ?? {};
  return op.security.some((requirement: Record<string, unknown>) =>
    Object.keys(requirement).every((name) => {
      const s = schemes[name];
      if (!s) return true;
      if (s.type === "apiKey") {
        if (s.in === "header") return !!req.headers[s.name.toLowerCase()];
        if (s.in === "query") return new URLSearchParams(req.query).has(s.name);
        if (s.in === "cookie") return cookies(req.headers.cookie).has(s.name);
      }
      return !!req.headers.authorization || s.type === "mutualTLS";
    }),
  );
}

/**
 * Everything wrong with a request for its operation: what an API that enforces its contract would
 * reject, plus what the contract never asked for (unknown query parameters, credentials sent to a
 * public operation). Empty when the request is right.
 */
export function validateRequest(op: Operation, req: Request): string[] {
  const problems: string[] = [];
  const contract = op.contract;
  const match = op.regex.exec(req.path);
  const query = new URLSearchParams(req.query);
  const cookieMap = cookies(req.headers.cookie);
  const declaredQuery = new Set(op.parameters.filter((p) => p.in === "query").map((p) => p.name));
  const schemes = contract.doc.components?.securitySchemes ?? contract.doc.securityDefinitions ?? {};
  for (const s of Object.values<any>(schemes)) if (s.type === "apiKey" && s.in === "query") declaredQuery.add(s.name);

  for (const p of op.parameters) {
    let values: string[] = [];
    if (p.in === "path") {
      const i = op.pathParams.indexOf(p.name);
      const raw = match?.[i + 1];
      if (raw !== undefined) {
        try {
          values = [decodeURIComponent(raw)];
        } catch {
          problems.push(`path ${p.name}: not percent-encoded correctly (${raw})`);
          continue;
        }
      }
    } else if (p.in === "query") values = query.getAll(p.name);
    else if (p.in === "header") values = req.headers[p.name.toLowerCase()] !== undefined ? [req.headers[p.name.toLowerCase()]] : [];
    else if (p.in === "cookie") values = cookieMap.has(p.name) ? [cookieMap.get(p.name)!] : [];
    if (!values.length) {
      if (p.required) problems.push(`${p.in} ${p.name}: required, not sent`);
      continue;
    }
    const isArray = typeOf(resolved(contract, p.schema)) === "array";
    if (!isArray && values.length > 1) problems.push(`${p.in} ${p.name}: sent ${values.length} times`);
    check(contract, p.schema, paramValue(contract, p, values), `${p.in} ${p.name}`, problems);
  }
  for (const name of new Set(query.keys())) {
    if (!declaredQuery.has(name)) problems.push(`query ${name}: not a parameter of ${op.key}`);
  }

  if (op.security.length === 0) {
    const sent = credentialsSent(op, req);
    if (sent.length) problems.push(`credentials sent to a public operation: ${sent.join(", ")}`);
  } else if (!credentialPresent(op, req)) {
    problems.push(`no credentials for ${op.key}`);
  }

  const contentType = mediaType(req.headers["content-type"]);
  if (!op.body) {
    if (req.body.length) problems.push(`a body (${contentType || "no content type"}) sent to ${op.key}, which takes none`);
    return problems;
  }
  if (!req.body.length) {
    if (op.body.required) problems.push(`body: required, not sent`);
    return problems;
  }
  const declared = Object.keys(op.body.content);
  const media = declared.find((d) => matchesMedia(d, contentType));
  if (!media) {
    problems.push(`body: Content-Type ${contentType || "(none)"} is not one of ${declared.join(", ")}`);
    return problems;
  }
  const { schema, encoding } = op.body.content[media];
  if (contentType.includes("json")) {
    let value: unknown;
    try {
      value = JSON.parse(req.body.toString("utf8"));
    } catch {
      problems.push(`body: not JSON`);
      return problems;
    }
    check(contract, schema, value, "body", problems);
  } else if (contentType === "application/x-www-form-urlencoded") {
    const s = resolved(contract, schema);
    const form = new URLSearchParams(req.body.toString("utf8"));
    const value: Record<string, unknown> = {};
    for (const key of new Set(form.keys())) {
      const prop = s.properties?.[key];
      const all = form.getAll(key);
      value[key] = prop && typeOf(resolved(contract, prop)) === "array" ? all.map((v) => coerce(contract, resolved(contract, prop).items ?? {}, v)) : coerce(contract, prop ?? {}, all[0]);
    }
    check(contract, schema, value, "form", problems);
  } else if (contentType === "multipart/form-data") {
    const parts = parseMultipart(req.headers["content-type"] ?? "", req.body);
    if (!parts) {
      problems.push(`multipart: no boundary in Content-Type`);
      return problems;
    }
    const s = resolved(contract, schema);
    for (const name of s.required ?? []) if (!parts.some((p) => p.name === name)) problems.push(`multipart: part ${name} is required, not sent`);
    for (const part of parts) {
      const prop = s.properties?.[part.name] ? resolved(contract, s.properties[part.name]) : undefined;
      if (!prop) {
        if (s.additionalProperties === false || s.properties) problems.push(`multipart: part ${part.name} is not in the schema`);
        continue;
      }
      const want = encoding?.[part.name]?.contentType;
      const type = typeOf(prop);
      if (want && !want.split(",").some((w) => matchesMedia(w.trim(), mediaType(part.contentType)))) {
        problems.push(`multipart: part ${part.name} has Content-Type ${part.contentType ?? "(none)"}, the contract says ${want}`);
      }
      if ((type === "object" || type === "array") && !(prop.format === "binary")) {
        try {
          check(contract, prop, JSON.parse(part.body.toString("utf8")), `multipart ${part.name}`, problems);
        } catch {
          problems.push(`multipart: part ${part.name} is not JSON`);
        }
      } else if (prop.format !== "binary" && type !== "string") {
        check(contract, prop, coerce(contract, prop, part.body.toString("utf8")), `multipart ${part.name}`, problems);
      }
    }
  }
  return problems;
}
