# Concepts & glossary

## People

| Term | Who |
|---|---|
| **Builder** | Uses the platform to define and build MCP servers. Usually a vendor or an internal integration team. |
| **Operator** | The customer who installs and runs a delivered server. Never needs access to the platform. |
| **Agent** | An AI agent using a delivered MCP server's tools, or using the platform's own MCP server to build servers. |

## Platform side

| Term | Meaning |
|---|---|
| **Workspace** | A team's space on the platform. Holds servers and the shared library. |
| **Shared library** | Things defined once and reused: API contracts, templates, naming policies, rule sets, pack configs, secret references. |
| **Server** | One MCP server the builder defines. Has one definition, many builds. |
| **Definition** | The source of truth for a server: a root `*.mcpgp.yaml` plus the files it references. Versioned. See [spec/definition-format.md](spec/definition-format.md). |
| **API** | One upstream HTTP API described by OpenAPI (e.g. Orders API, Jira). A server has one or more. |
| **Contract** | One version of an API's OpenAPI document (e.g. Orders API 1.0 in Swagger 2.0, Orders API 2.0 in OAS 3.1). A server ships one or more contracts per API. |
| **Operation** | One OpenAPI operation (method + path). Candidate for a tool. |
| **Rule** | A selector plus changes (`set`, `prepend`, `append`, `replace`, `template`, `hidden`) applied to tools. Rules are ordered. |
| **Naming policy** | The pipeline that turns an operation into a tool name. See [spec/naming.md](spec/naming.md). |
| **Exposure** | How tools reach `tools/list`: `plain` (all), `discovery` (search tools only), `hybrid` (pinned + search). |
| **Support pack** | A group of tools that don't come from a contract: audit, health, diagnostics, recording, issue tracker, skills, feedback. |
| **Variant** | A server that `extends` another and stores only its differences. |
| **Duplicate** | An independent copy of a server's definition. |
| **Manifest** | The fully resolved, language-neutral description of a server produced by the compiler: tools, schemas, names with provenance, policies. Runtimes execute it. |
| **Build** | One run of the compiler and packager for a definition version. Produces a package. Can be downloaded, archived, deleted. |
| **Package** | What is delivered: jar, desktop bundle (`.mcpb`), Docker image, docs, config schema, signed checksums. Self-contained: runs at the customer without the platform. |
| **Generation mode** | `runtime` (tools built at startup from the manifest), `codegen` (Java source generated), or `both`. |

## Client side

| Term | Meaning |
|---|---|
| **Runtime config** | The operator's `<server>.yaml` (+ `.env`). See [spec/runtime-config.md](spec/runtime-config.md). |
| **Instance / target server** | One upstream server the MCP server calls (e.g. `prod-eu` at `https://orders-eu…`). Configured by the operator only. |
| **Single-instance build** | Exactly one target server; tools have no `instance` parameter. |
| **Multi-instance build** | Several target servers; every tool requires an `instance` parameter. No default. |
| **Contract selection** | Per instance: `auto` (detect at startup) or a pinned version (`"1.0"`). |
| **Inbound auth** | Client → MCP server: `none`, `static` (API key), `oauth` (OAuth 2.1 resource server). |
| **Downstream auth** | MCP server → target server: one type per instance, chosen from the types the build supports. |
| **Audit file** | JSON Lines file of audit events written by the server. See [spec/audit-and-errors.md](spec/audit-and-errors.md). |
| **Telemetry** | OpenTelemetry (OTLP push), off by default. Never includes tool arguments. |

## Words we avoid

- "Environment" for an instance: too ambiguous. Say **instance** or **target server**.
- "Deploy" for delivering a package: the platform never deploys at a customer. Say **deliver**.
- "Default instance": doesn't exist in multi-instance builds (see [ADR-0004](decisions/0004-instance-required-parameter.md)).
