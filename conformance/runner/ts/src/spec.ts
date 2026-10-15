import SwaggerParser from "@apidevtools/swagger-parser";
import { Ajv, type ValidateFunction } from "ajv";
import { Ajv2020 } from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

/**
 * A contract read independently of mcpgp: its operations, and a JSON Schema validator for the
 * schemas in it. Swagger 2.0, OpenAPI 3.0 and 3.1.
 */
export interface Contract {
  file: string;
  format: "swagger-2.0" | "openapi-3.0" | "openapi-3.1";
  basePath: string;
  operations: Operation[];
  doc: any;
  /** Resolves a local `$ref` (#/components/…, #/definitions/…). */
  resolve(ref: string): any;
  /** A validator for a schema found in the contract (its local $refs resolve within the contract). */
  validator(schema: any): ValidateFunction;
}

export interface Parameter {
  name: string;
  in: "path" | "query" | "header" | "cookie";
  required: boolean;
  schema: any;
  style?: string;
  explode?: boolean;
}

export interface MediaType {
  schema?: any;
  example?: any;
  encoding?: Record<string, { contentType?: string }>;
}

export interface Operation {
  key: string;
  method: string;
  /** The path template with the base path, e.g. /api/items/{itemId}. */
  path: string;
  regex: RegExp;
  pathParams: string[];
  parameters: Parameter[];
  body?: { required: boolean; content: Record<string, MediaType> };
  responses: Record<string, { content: Record<string, MediaType>; headers: Record<string, any> }>;
  /** Security requirements in effect; [] means a public operation. */
  security: any[];
  contract: Contract;
}

const METHODS = ["get", "put", "post", "delete", "options", "head", "patch", "trace"];

function pointer(doc: any, ref: string): any {
  if (!ref.startsWith("#/")) throw new Error(`only local $refs are supported in the gate: ${ref}`);
  return ref
    .slice(2)
    .split("/")
    .map((p) => p.replace(/~1/g, "/").replace(/~0/g, "~"))
    .reduce((node, key) => node?.[decodeURIComponent(key)], doc);
}

/** OpenAPI 3.0 / Swagger 2.0 schema dialect → JSON Schema draft 7 (nullable, boolean exclusive bounds). */
function toDraft7(node: any): any {
  if (Array.isArray(node)) return node.map(toDraft7);
  if (!node || typeof node !== "object") return node;
  const out: any = {};
  for (const [k, v] of Object.entries(node)) out[k] = toDraft7(v);
  const nullable = out.nullable === true || out["x-nullable"] === true;
  delete out.nullable;
  if (nullable && typeof out.type === "string") out.type = [out.type, "null"];
  if (out.exclusiveMinimum === true && typeof out.minimum === "number") {
    out.exclusiveMinimum = out.minimum;
    delete out.minimum;
  } else if (typeof out.exclusiveMinimum === "boolean") delete out.exclusiveMinimum;
  if (out.exclusiveMaximum === true && typeof out.maximum === "number") {
    out.exclusiveMaximum = out.maximum;
    delete out.maximum;
  } else if (typeof out.exclusiveMaximum === "boolean") delete out.exclusiveMaximum;
  return out;
}

/** Local refs inside a schema point into the contract document registered as `contract`. */
function rebase(node: any): any {
  if (Array.isArray(node)) return node.map(rebase);
  if (!node || typeof node !== "object") return node;
  const out: any = {};
  for (const [k, v] of Object.entries(node)) out[k] = k === "$ref" && typeof v === "string" && v.startsWith("#/") ? `contract${v}` : rebase(v);
  return out;
}

function basePathOf(doc: any): string {
  if (doc.swagger) return (doc.basePath ?? "").replace(/\/$/, "");
  const url: string | undefined = doc.servers?.[0]?.url;
  if (!url) return "";
  const vars = doc.servers[0].variables ?? {};
  const filled = url.replace(/\{([^}]+)\}/g, (_: string, name: string) => vars[name]?.default ?? "");
  try {
    return new URL(filled, "http://placeholder").pathname.replace(/\/$/, "");
  } catch {
    return "";
  }
}

export async function loadContract(file: string): Promise<Contract> {
  // bundle: external files inlined, internal $refs kept (schemas may be recursive)
  const doc: any = await SwaggerParser.bundle(file);
  const format = doc.swagger ? "swagger-2.0" : String(doc.openapi).startsWith("3.1") ? "openapi-3.1" : "openapi-3.0";
  const schemaDoc = format === "openapi-3.1" ? doc : toDraft7(doc);
  const ajv = format === "openapi-3.1"
    ? new Ajv2020({ strict: false, allErrors: true, validateSchema: false, logger: false })
    : new Ajv({ strict: false, allErrors: true, validateSchema: false, logger: false });
  (addFormats as any).default ? (addFormats as any).default(ajv) : (addFormats as any)(ajv);
  for (const name of ["binary", "password", "int32", "int64", "float", "double", "byte"]) {
    if (!ajv.formats[name]) ajv.addFormat(name, true);
  }
  ajv.addSchema(schemaDoc, "contract");
  const cache = new Map<any, ValidateFunction>();

  const contract: Contract = {
    file,
    format,
    basePath: basePathOf(doc),
    operations: [],
    doc,
    resolve: (ref) => pointer(doc, ref),
    validator(schema) {
      let fn = cache.get(schema);
      if (!fn) {
        const prepared = rebase(format === "openapi-3.1" ? schema : toDraft7(schema));
        fn = ajv.compile(prepared);
        cache.set(schema, fn);
      }
      return fn;
    },
  };
  const deref = (node: any) => (node?.$ref ? pointer(doc, node.$ref) : node);

  for (const [template, rawItem] of Object.entries<any>(doc.paths ?? {})) {
    const item = deref(rawItem);
    for (const method of METHODS) {
      const op = item?.[method];
      if (!op) continue;
      const params = new Map<string, any>();
      for (const p of [...(item.parameters ?? []), ...(op.parameters ?? [])].map(deref)) params.set(`${p.in}:${p.name}`, p);
      const parameters: Parameter[] = [];
      let body: Operation["body"];
      const consumes: string[] = op.consumes ?? doc.consumes ?? ["application/json"];
      const form: Record<string, any> = {};
      const formRequired: string[] = [];
      for (const p of params.values()) {
        if (p.in === "body") {
          body = { required: !!p.required, content: Object.fromEntries(consumes.map((c) => [c, { schema: p.schema }])) };
        } else if (p.in === "formData") {
          form[p.name] = p.type === "file" ? { type: "string", format: "binary" } : { ...p, in: undefined, name: undefined, required: undefined };
          if (p.required) formRequired.push(p.name);
        } else {
          const schema = p.schema ?? { type: p.type, items: p.items, enum: p.enum, format: p.format, minimum: p.minimum, maximum: p.maximum, pattern: p.pattern, maxLength: p.maxLength };
          parameters.push({ name: p.name, in: p.in, required: !!p.required || p.in === "path", schema, style: p.style, explode: p.explode ?? (p.collectionFormat === "multi" ? true : undefined) });
        }
      }
      if (Object.keys(form).length) {
        const schema = { type: "object", properties: form, required: formRequired };
        body = { required: formRequired.length > 0, content: Object.fromEntries(consumes.map((c) => [c, { schema }])) };
      }
      if (op.requestBody) {
        const rb = deref(op.requestBody);
        body = { required: !!rb.required, content: rb.content ?? {} };
      }
      const responses: Operation["responses"] = {};
      for (const [status, rawResponse] of Object.entries<any>(op.responses ?? {})) {
        const r = deref(rawResponse);
        let content: Record<string, MediaType> = r.content ?? {};
        if (doc.swagger && r.schema) {
          const produces: string[] = op.produces ?? doc.produces ?? ["application/json"];
          content = Object.fromEntries(produces.map((p) => [p, { schema: r.schema, example: r.examples?.[p] }]));
        }
        responses[status] = { content, headers: r.headers ?? {} };
      }
      const fullPath = contract.basePath + template;
      const pathParams: string[] = [];
      const regex = new RegExp(
        "^" + fullPath.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\{([^}]+)\}/g, (_m, name) => {
          pathParams.push(name);
          return "([^/]+)";
        }) + "/?$",
      );
      contract.operations.push({
        key: `${method.toUpperCase()} ${fullPath}`,
        method: method.toUpperCase(),
        path: fullPath,
        regex,
        pathParams,
        parameters,
        body,
        responses,
        security: op.security ?? doc.security ?? [],
        contract,
      });
    }
  }
  return contract;
}

/** The operation a request is for: a literal path wins over a template (/items/search over /items/{id}). */
export function findOperation(contracts: Contract[], method: string, path: string): Operation | undefined {
  const candidates = contracts.flatMap((c) => c.operations).filter((op) => op.method === method && op.regex.test(path));
  return candidates.sort((a, b) => a.pathParams.length - b.pathParams.length)[0];
}
