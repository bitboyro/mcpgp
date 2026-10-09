# Architecture

## Principles

1. **One language-neutral core.** OpenAPI is parsed in exactly one place: the compiler. Everything
   downstream consumes the **manifest**. Runtimes in other languages never parse OpenAPI.
2. **Same model, two outputs.** Runtime mode interprets the manifest; codegen emits classes that
   implement the same interfaces. Every exposure mode, instance mode and pack works with both.
3. **Specs before code.** The definition format, runtime config, manifest, audit events and error
   codes are versioned specs with JSON Schemas. Implementations are checked against them.
4. **Self-contained delivery.** A package runs on the customer's infrastructure, connected only to
   their own systems. It never needs the platform, the vendor, the internet or a licence call.
5. **Explainable output.** Every generated value carries provenance (which file, line, rule).

## Big picture

```
                ┌────────────────────────── platform ──────────────────────────┐
  Web UI ──┐    │                                                              │
  CLI ─────┼──► │  REST API ──► Definition store ──► Compiler ──► Manifest ──► Packager ──► Package
  Agents ──┘    │  (MCP server   (versioned,        (parse,       (JSON,       (jar, .mcpb,
  (via MCP)     │   on top)       multi-file)        resolve,      provenance)  Docker,      
                │                                    validate)                  docs, schema)
                └──────────────────────────────────────────────────────────────┘
                                                                         │ delivered
                                                     ┌───────────────────▼────────────────────┐
                                                     │ Customer: runtime (Java now)            │
                                                     │ manifest + runtime config → MCP server  │
                                                     └─────────────────────────────────────────┘
```

## Compiler pipeline

```
sources ─► resolve files ─► parse contracts ─► normalize ─► select ─► name ─► apply rules
        ─► shape schemas ─► attach packs ─► attach policies ─► validate ─► manifest + diagnostics + lock
```

| Stage | Responsibility |
|---|---|
| Resolve files | Load the root definition, follow `$ref` / `include`, enforce root restriction, detect cycles. |
| Parse contracts | Swagger 2.0 / OAS 3.0 / 3.1 → one internal operation model per contract version. |
| Normalize | Flatten `allOf`, resolve discriminators where possible, drop `readOnly` from inputs, apply overlays. |
| Select | Access preset, include/exclude selectors, deprecated handling, per-version availability. |
| Name | Naming pipeline ([spec/naming.md](spec/naming.md)) + lock file. |
| Apply rules | Ordered override rules on titles, descriptions, params, annotations. |
| Shape schemas | Input schema per [spec/tools-contract.md](spec/tools-contract.md), `instance` parameter in multi builds. |
| Attach packs | Resolve support packs, their tools and their config requirements. |
| Attach policies | Auth types, redaction, limits, audit, telemetry defaults, exposure. |
| Validate | Collisions, limits, schema validity. |

## Repository layout (planned)

```
mcpgp/
├─ docs/                  specs, decisions, process (this)
├─ examples/              platform-side definitions and client-side configs
├─ schemas/               Maven module: JSON Schemas for every format + validator          (M1)
├─ conformance/           fixtures + expected manifests + expected MCP traffic             (M1)
├─ core/                  compiler: model, resolver, naming, rules, validation   (Java)    (M2)
├─ runtime-java/          manifest interpreter, HTTP invoker, MCP adapter        (Java)    (M3)
├─ packs/                 support packs                                          (Java)    (M3)
├─ cli/                   mcpgp CLI                                              (Java)    (M3)
├─ packager/              jar, .mcpb, Docker, docs, schema generation                      (M4)
├─ platform-api/          REST API + definition store + builds                             (M5)
└─ web/                   web UI                                                            (M6)
```

## Key interfaces (language-neutral)

| Contract | Format | Owned by |
|---|---|---|
| Definition | YAML, JSON Schema [definition.schema.json](../schemas/src/main/resources/schemas/definition.schema.json) | [spec/definition-format.md](spec/definition-format.md) |
| Manifest | JSON, [manifest.schema.json](../schemas/src/main/resources/schemas/manifest.schema.json) | this document + conformance suite |
| Runtime config | YAML, schema generated per build from the manifest | [spec/runtime-config.md](spec/runtime-config.md) |
| Tool contract | MCP `tools/list` / `tools/call` shapes | [spec/tools-contract.md](spec/tools-contract.md) |
| Audit event, error codes | JSON Lines, enum | [spec/audit-and-errors.md](spec/audit-and-errors.md) |
| Pack descriptor | YAML | [spec/packaging.md](spec/packaging.md) |

## Runtime (Java)

- MCP via the official MCP Java SDK; Spring Boot packaging for the default target.
- Stateless Streamable HTTP by default; stateful only when a feature needs server→client
  messages (progress, elicitation).
- Per-instance HTTP client with its own auth, TLS and timeouts; no automatic retries of upstream calls.
- Audit writer: file (JSON Lines), never stdout in stdio mode.
- Telemetry: OpenTelemetry SDK, OTLP exporter, disabled unless configured.

## What the platform never does

- Call a customer's target servers.
- Deploy into a customer environment.
- Receive data from delivered servers (no phone-home).
