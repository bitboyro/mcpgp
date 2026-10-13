# Spec: manifest

Status: **draft v1 — accepted** · Owner: core · Schema: [manifest.schema.json](../../schemas/src/main/resources/schemas/manifest.schema.json)
Decision: [ADR-0002](../decisions/0002-language-neutral-manifest.md)

The manifest is the compiler's output and the only input a runtime needs besides the operator's
runtime config. It is fully resolved: a runtime never parses OpenAPI, never applies rules, never
computes names. It is JSON.

**Location:** the manifest is **embedded in the server jar** (`/mcpgp/manifest.json` on the classpath)
and that embedded copy is the only one the runtime reads, so a jar can never run with a mismatching
manifest. A copy sits next to the jar in the package (`manifest.json`) for review, diffs and tooling;
its checksum is in the signed `SHA256SUMS`. Other targets (desktop bundle, Docker image) contain the
same jar.

Full example: [examples/manifest/orders-mcp.manifest.json](../../examples/manifest/orders-mcp.manifest.json).

## 1. Principles

1. **Self-sufficient.** Everything needed to list tools, validate input, build HTTP requests, map
   responses and enforce policies is in the manifest.
2. **Served as is.** Tool `name`, `title`, `description`, `inputSchema` and `annotations` are exactly
   what `tools/list` returns (before per-revision feature gating, compatibility.md §1.2). Only the
   `instance` enum is filled in at startup.
3. **Deterministic.** Same inputs → byte-identical manifest (§9).
4. **Explainable.** Every generated name, title and description carries its provenance.
5. **Versioned.** `manifestVersion` major changes only for breaking changes; runtimes refuse unknown
   majors with a clear message.

## 2. Top level

```json
{
  "manifestVersion": "1.0",
  "server":      { … },   // identity and instructions (§3)
  "build":       { … },   // where it came from (§3)
  "runtime":     { … },   // what the runtime must support (§3)
  "instances":   { … },   // single/multi and policies (§4)
  "apis":        [ … ],   // APIs, contracts, auth, paging (§4)
  "exposure":    { … },   // plain / discovery / toolsets / hybrid (§4)
  "resources":   { … },   // GET operations exposed as resources, when there are any (tools-contract §14.1)
  "prompts":     [ … ],   // prompt templates, when there are any (tools-contract §14.2)
  "policies":    { … },   // safety, files, redaction, defaults and limits for runtime config (§4)
  "tools":       [ … ],   // every tool, sorted by name (§5)
  "packs":       [ … ],   // support packs and their requirements (§6)
  "schemas":     { … },   // shared schema definitions per contract (§7)
  "diagnostics": [ … ]    // warnings/info from the build (§8)
}
```

## 3. `server`, `build`, `runtime`

```json
"server": {
  "name": "orders-mcp",
  "title": "Example Orders API",
  "version": "2.0.0+3",
  "instructions": "Look up and manage customers, accounts, orders and invoices…"
},
"build": {
  "createdAt": "2026-10-09T10:40:00Z",           // from SOURCE_DATE_EPOCH or the commit time
  "cli": "0.4.2",
  "definition": { "name": "orders-mcp", "source": "git+https://…#9c41e7a", "digest": "sha256:…" },
  "lock":       { "digest": "sha256:…" },
  "extends":    null                              // or { "name": "orders-mcp", "version": "1.4.1" } for variants
},
"runtime": {
  "minRuntime": "0.4.0",
  "mcpRevisions": ["2025-03-26", "2025-06-18", "2025-11-25"],
  "transports": { "allowed": ["stdio", "http"], "default": "stdio" },
  "inboundAuth": { "allowed": ["none", "static", "oauth"], "default": "none" }
}
```

## 4. `instances`, `apis`, `exposure`, `policies`

```json
"instances": { "mode": "multi", "mixedContracts": "union", "max": null },

"apis": [{
  "id": "orders",
  "title": "Example Orders API",
  "toolPrefix": "orders",
  "contracts": [
    { "version": "1.0", "format": "swagger-2.0", "digest": "sha256:…", "basePath": "/api/v1", "default": false },
    { "version": "2.0", "format": "openapi-3.1", "digest": "sha256:…", "basePath": "/api/v2", "default": true }
  ],
  "detect": { "method": "GET", "path": "/version", "underBasePath": true, "field": "/version", "pattern": "^(\\d+\\.\\d+)" },
  "downstreamAuth": {
    "supported": ["basic", "apiKey", "oauth2ClientCredentials", "mtls"],
    "identity": "service-account",
    "unsatisfiedTools": "hide",
    "apiKey": { "in": "header", "name": "X-Api-Key" }
    // "fromRequest": { "header": "X-Orders-Authorization" }: when the definition lets clients send the credential (auth.md §2.4)
    // "schemes": { "apiKey": "apiKey", "basicAuth": "basic", "oauth": "oauth2ClientCredentials" }: what each scheme the tools' security names is (auth.md §2.2)
  },
  "pagination": null
}],

"exposure": { "mode": "hybrid", "pinned": ["orders_customer_get"], "plainSearch": false },
// toolsets mode: the groups, resolved
"exposure": { "mode": "toolsets", "toolsets": { "customers": { "description": "Operations tagged customers",
              "tools": ["orders_customer_create", "orders_customer_list"] } } },

"policies": {
  "confirmDestructive": true,
  "responseLimitKb": 256,
  "files": { "forms": ["base64", "path"] },
  "redaction": { "fields": ["password", "secret", "token", "apiKey", "paymentToken"] },
  "baseUrlAllowlist": [],
  "runtimeDefaults": {
    "timeouts": { "defaultSeconds": 30, "maxSeconds": 120 },
    "audit": { "file": { "enabled": true, "rotate": "daily", "retainDays": 30 }, "arguments": "redacted" },
    "telemetry": { "enabled": false }
  }
}
```

## 5. Tools

Each tool has a **public part** (served to agents) and an **execution part** (used by the runtime).

```json
{
  "name": "orders_customer_create_multipart",
  "kind": "api",                                   // api | builtin | pack
  "title": "Create customer with documents",
  "description": "[Orders] Creates a customer and uploads its documents…",
  "annotations": { "readOnlyHint": false, "destructiveHint": false, "idempotentHint": false, "openWorldHint": true },
  "sensitive": false,
  "confirm": false,
  "inputSchema": { … },                            // final, self-contained, grouped shape (tools-contract §2)
  "outputSchema": null,

  "api": "orders",
  "operation": { "key": "orders:POST:/customers", "operationId": "createCustomer", "tags": ["customers"] },
  "variants": {                                    // one per contract where the operation exists
    "2.0": {
      "method": "POST",
      "path": "/customers",
      "parameters": [],
      "body": {
        "contentType": "multipart/form-data",
        "required": true,
        "schema": { "$ref": "#/schemas/orders@2.0/CustomerUpload" },
        "parts": [
          { "name": "payload", "contentTypes": ["application/json"], "file": false, "secret": false, "required": true },
          { "name": "document", "contentTypes": ["application/pdf"], "file": true, "secret": false, "required": false }
        ]
      },
      "accept": ["application/json", "application/problem+json"],
      "security": [[{ "scheme": "basicAuth" }], [{ "scheme": "apiKey" }]],
      "responses": [
        { "status": "201", "contentType": "application/json", "schema": { "$ref": "#/schemas/orders@2.0/Customer" } },
        { "status": "default", "contentType": "application/problem+json", "error": true }
      ]
    }
  },
  "provenance": {
    "name": [
      { "step": "source", "value": "createCustomer" },
      { "step": "shape", "value": "customer_create" },
      { "step": "contentTypeSuffix", "value": "customer_create_multipart" },
      { "step": "namespace", "value": "orders_customer_create_multipart" },
      { "step": "lock", "file": "mcp-names.lock", "since": "1.3.0" }
    ],
    "description": [
      { "step": "spec", "file": "contracts/orders-2.0.yaml", "pointer": "/paths/~1customers/post/description" },
      { "step": "rule", "file": "overrides/10-customers.yaml", "line": 2, "op": "prepend" }
    ]
  }
}
```

Rules:
- `variants` is keyed by contract version. A tool available in only some contracts lists only those;
  calls against an instance using another contract return `NOT_AVAILABLE_ON_INSTANCE`.
- `parameters[]`: `{ name, in, required, style, explode, schema }`, in the grouped shape's order.
- `security` mirrors the OpenAPI requirement (alternatives of AND-sets) and is used to hide or explain
  tools the instance's sign-in can't use. `"security": []` marks a public operation (the spec's
  `security: []`): the runtime sends no credentials for it. Absent means none was declared.
- `kind: builtin` (e.g. `instances_list`, `search_tools`) and `kind: pack` tools have no `variants`;
  they carry `handler: "<builtin id>"` or `pack: "<pack id>"`.
- Hidden tools are not in the manifest. Excluded operations are listed only in `diagnostics` (info).

## 6. Packs

```json
"packs": [
  { "id": "recording", "version": "1", "tools": ["orders_recording_start", "orders_recording_stop", "orders_recording_list", "orders_recording_sessions"],
    "requires": { "config": false } },
  { "id": "issue-tracker", "version": "1", "tools": [],
    "requires": { "config": true, "configSchema": "#/packs/issue-tracker/config" } }
]
```

Tools of packs whose required config is missing at runtime are not registered (packaging.md §4).
Pack tool definitions appear in `tools` like any other tool, with `kind: "pack"`.

## 7. Schemas

```json
"schemas": {
  "orders@2.0": { "Customer": { … }, "CustomerUpload": { … } },
  "orders@1.0": { "Customer": { … } }
}
```

- Shared definitions, used by `variants` for validation and request building, referenced as
  `#/schemas/<api>@<contract>/<Name>`.
- Already normalized: discriminators flattened, `readOnly` removed from request schemas, nullable
  types normalized (JSON Schema 2020-12).
- `inputSchema` in each tool is self-contained (its own `$defs`), so it can be served without
  rewriting references.

## 8. Diagnostics

```json
"diagnostics": [
  { "level": "warning", "code": "RULE_MATCHES_NOTHING", "message": "Rule matches no operation in contract 1.0", "location": { "file": "overrides/10-customers.yaml", "line": 14 } },
  { "level": "info", "code": "OPERATION_EXCLUDED", "operation": "orders:DELETE:/customers/{id}", "reason": "access: read-write" }
]
```

Errors never reach a manifest: a build with errors produces no manifest.

## 9. Determinism and integrity

- Canonical JSON (RFC 8785): sorted keys, no insignificant whitespace in the digest computation.
- `tools` sorted by `name`; `apis`, `contracts`, `packs` in definition order; `diagnostics` sorted by
  file, line, code.
- The only timestamp is `build.createdAt`, taken from `SOURCE_DATE_EPOCH` or the commit time.
- The manifest's own digest is listed in the package's `SHA256SUMS`, which is signed.

## 10. Versioning

- `manifestVersion` `1.x`: additive changes only (new optional fields). Runtimes ignore unknown fields.
- A new major is needed to remove, rename or change the meaning of a field. A runtime refuses a manifest
  whose major it doesn't know, naming both versions.

## 11. Decisions

1. Instance address without a path gets the contract's `basePath` appended; with a path, used as is
   (runtime-config §4.1).
2. Each tool's `inputSchema` is self-contained (its own `$defs`); packages are compressed.
3. Pack tool definitions are included in the manifest, so they are reviewed and diffed like API tools.
4. Excluded operations appear only in `diagnostics` (info), not as tool stubs.
