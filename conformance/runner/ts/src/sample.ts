/**
 * Values that satisfy a JSON Schema: response bodies for the mock, and tool arguments for generated
 * tests (S3). Deterministic: the same schema and options give the same value.
 */

export interface SampleOptions {
  /** Optional properties: all of them (up to `maxDepth`), or none. */
  optional: "all" | "none";
  /** Which branch of oneOf/anyOf to take (wraps around). */
  branch?: number;
  /** Use `example`/`examples`/`default` from the schema when present (responses: yes, arguments: no). */
  examples?: boolean;
  maxDepth?: number;
  /** Resolves a `$ref` to its schema. */
  resolve: (ref: string) => any;
}

const FORMATS: Record<string, string> = {
  "date-time": "2026-01-01T00:00:00Z",
  date: "2026-01-01",
  time: "12:00:00Z",
  email: "gate@example.com",
  uuid: "00000000-0000-4000-8000-000000000000",
  uri: "https://example.com/gate",
  url: "https://example.com/gate",
  hostname: "example.com",
  ipv4: "192.0.2.1",
  ipv6: "2001:db8::1",
  byte: "Z2F0ZQ==",
  binary: "gate",
};

export function sample(schema: any, options: SampleOptions, depth = 0): any {
  const maxDepth = options.maxDepth ?? 3;
  if (schema === true || schema === undefined || schema === null) return "gate";
  if (schema.$ref) return sample(options.resolve(schema.$ref), options, depth + 1);
  if ("const" in schema) return schema.const;
  if (options.examples) {
    if ("example" in schema) return schema.example;
    if (Array.isArray(schema.examples) && schema.examples.length) return schema.examples[0];
    if ("default" in schema) return schema.default;
  }
  if (Array.isArray(schema.enum) && schema.enum.length) return schema.enum[(options.branch ?? 0) % schema.enum.length];
  if (Array.isArray(schema.allOf)) {
    const merged: any = {};
    for (const part of schema.allOf) {
      const value = sample(part, options, depth + 1);
      if (value && typeof value === "object" && !Array.isArray(value)) Object.assign(merged, value);
    }
    const own = { ...schema };
    delete own.allOf;
    if (own.properties || own.type) Object.assign(merged, sample(own, options, depth));
    return merged;
  }
  for (const key of ["oneOf", "anyOf"]) {
    if (Array.isArray(schema[key]) && schema[key].length) {
      const branches = schema[key].filter((b: any) => !isNullType(b, options));
      const list = branches.length ? branches : schema[key];
      return sample(list[(options.branch ?? 0) % list.length], options, depth + 1);
    }
  }
  const type = typeOf(schema);
  switch (type) {
    case "object": {
      const value: Record<string, any> = {};
      const required: string[] = schema.required ?? [];
      const properties: Record<string, any> = schema.properties ?? {};
      for (const [name, property] of Object.entries(properties)) {
        const take = required.includes(name) || (options.optional === "all" && depth < maxDepth);
        if (take) value[name] = sample(property, options, depth + 1);
      }
      const min = schema.minProperties ?? 0;
      for (const [name, property] of Object.entries(properties)) {
        if (Object.keys(value).length >= min) break;
        if (!(name in value)) value[name] = sample(property, options, depth + 1);
      }
      if (!Object.keys(properties).length && schema.additionalProperties && typeof schema.additionalProperties === "object"
          && (min > 0 || (options.optional === "all" && depth < maxDepth))) {
        value.gate = sample(schema.additionalProperties, options, depth + 1);
      }
      return value;
    }
    case "array": {
      const min = schema.minItems ?? 0;
      const want = Math.max(min, options.optional === "all" && depth < maxDepth ? 1 : 0);
      const count = Math.min(want, schema.maxItems ?? want);
      return Array.from({ length: count }, (_, i) => sample(schema.items ?? {}, { ...options, branch: (options.branch ?? 0) + i }, depth + 1));
    }
    case "integer":
    case "number":
      return number(schema, type === "integer");
    case "boolean":
      return true;
    case "null":
      return null;
    default:
      return string(schema);
  }
}

function isNullType(schema: any, options: SampleOptions): boolean {
  const s = schema?.$ref ? options.resolve(schema.$ref) : schema;
  return s?.type === "null";
}

export function typeOf(schema: any): string {
  if (Array.isArray(schema.type)) return schema.type.find((t: string) => t !== "null") ?? "null";
  if (schema.type) return schema.type;
  if (schema.properties || schema.additionalProperties) return "object";
  if (schema.items) return "array";
  return "string";
}

function number(schema: any, integer: boolean): number {
  let value = 1;
  if (typeof schema.minimum === "number") value = schema.minimum;
  if (typeof schema.exclusiveMinimum === "number") value = schema.exclusiveMinimum + (integer ? 1 : 0.5);
  if (typeof schema.maximum === "number" && value > schema.maximum) value = schema.maximum;
  if (typeof schema.exclusiveMaximum === "number" && value >= schema.exclusiveMaximum) value = schema.exclusiveMaximum - (integer ? 1 : 0.5);
  if (typeof schema.multipleOf === "number") value = Math.ceil(value / schema.multipleOf) * schema.multipleOf;
  return integer ? Math.round(value) : value;
}

function string(schema: any): string {
  if (schema.contentEncoding === "base64") return "Z2F0ZQ==";
  let value = (schema.format && FORMATS[schema.format]) ?? "gate";
  if (schema.pattern) value = fromPattern(schema.pattern) ?? value;
  const min = schema.minLength ?? 0;
  const max = schema.maxLength ?? Infinity;
  while (value.length < min) value += value.slice(-1) || "a";
  if (value.length > max) value = value.slice(0, max);
  return value;
}

/** A string matching a simple pattern (anchors, literals, classes and quantifiers), else a guess that matches. */
export function fromPattern(pattern: string): string | undefined {
  let regex: RegExp;
  try {
    regex = new RegExp(pattern, "u");
  } catch {
    return undefined;
  }
  const built = build(pattern);
  if (built !== undefined && regex.test(built)) return built;
  for (const guess of ["gate", "a", "abc", "a1", "gate-1", "1", "A", "Gate", "x_1", "a.b"]) {
    if (regex.test(guess)) return guess;
  }
  return undefined;
}

function build(pattern: string): string | undefined {
  let out = "";
  let i = 0;
  const src = pattern.replace(/^\^/, "").replace(/\$$/, "");
  while (i < src.length) {
    let atom: string;
    const c = src[i];
    if (c === "[") {
      const end = src.indexOf("]", i);
      if (end < 0) return undefined;
      const body = src.slice(i + 1, end);
      if (body.startsWith("^")) return undefined;
      atom = body.startsWith("\\d") ? "1" : body[0] === "\\" ? body[1] : body[0];
      i = end + 1;
    } else if (c === "\\") {
      const e = src[i + 1];
      atom = e === "d" ? "1" : e === "w" ? "a" : e === "s" ? " " : e;
      i += 2;
    } else if (c === "(" || c === ")" || c === "|") {
      return undefined;
    } else if (c === ".") {
      atom = "a";
      i += 1;
    } else {
      atom = c;
      i += 1;
    }
    let count = 1;
    const q = src[i];
    if (q === "{") {
      const end = src.indexOf("}", i);
      const [min] = src.slice(i + 1, end).split(",");
      count = Math.max(1, Number(min) || 0);
      i = end + 1;
    } else if (q === "+") {
      i += 1;
    } else if (q === "*" || q === "?") {
      count = 1;
      i += 1;
    }
    out += atom.repeat(count);
  }
  return out;
}
