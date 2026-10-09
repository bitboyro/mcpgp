# Requirements

Every configurable thing, grouped by concern.

**Set by**
- **B**: build definition (`*.mcpgp.yaml`), edited by the builder.
- **R**: runtime config (`<server>.yaml`), edited by the operator.
- **B→R**: the build sets the default and what is allowed; the operator may override within that.
- **P**: platform settings (workspace, identities), not part of a definition.

**Priority**: **P0** first usable version · **P1** next · **P2** later.

## 1. Workspace & servers
| Requirement | Set by | Prio |
|---|---|---|
| Workspaces with several servers | P | P0 |
| Server can combine several APIs (tools prefixed per API) | B | P1 |
| Duplicate a server (independent copy) | P | P0 |
| Variant: `extends` another server, store only overrides | B | P1 |
| Shared library: contracts, templates, naming policies, rule sets, pack configs, secret references | P | P1 |

## 2. Definition & sources
| Requirement | Set by | Prio |
|---|---|---|
| Versioned format (`apiVersion: mcpgp/v1`) with migrations | B | P0 |
| Multi-file definitions: `$ref`, `include` globs, merge rules, root restriction, cycle detection | B | P0 |
| Sources: upload (file or bundle), local path (CLI) | B | P0 |
| Sources: URL with checksum, git (repo, ref, path) pinned to a commit | B | P1 |
| Export (tree or flattened) / import with diff and dry run | B | P0 |
| Remote references only to allow-listed hosts | B | P1 |
| Webhook-triggered rebuilds | P | P2 |

## 3. Contracts
| Requirement | Set by | Prio |
|---|---|---|
| Swagger 2.0, OpenAPI 3.0, 3.1 per contract; mixed formats per API | B | P0 |
| Several contract versions per API; default version | B | P0 |
| Per-version tool availability (computed) | B | P0 |
| Version detection rule (endpoint + pattern) | B | P1 |
| Overlays; spec normalizer filters | B | P1 |
| OpenAPI 3.2 | B | P2 |

## 4. Tools: selection, naming, shape
| Requirement | Set by | Prio |
|---|---|---|
| Include/exclude by tag, path, method, operationId; hide deprecated | B | P0 |
| Access presets: read only, read & write (no delete), everything | B | P0 |
| Naming pipeline (source, normalize, shape, vocabulary, rules, explicit, namespace, limits, collisions) | B | P0 |
| Override rules: `set`, `prepend`, `append` on name/title/description/params/annotations | B | P0 |
| `replace` (regex), `template` | B | P1 |
| Name lock file; rename preview | B | P1 |
| Aliases for renamed tools during a deprecation window | B | P2 |
| Provenance for every field (`explain`) | B | P1 |
| Input schema shape (fixed: see [spec/tools-contract.md](spec/tools-contract.md)) | B | P0 |
| Response size limit / truncation | B→R | P0 |
| `outputSchema` + `structuredContent` | B | P1 |
| One request content type per tool, decided at build time (`split` / `prefer`); `Accept` never collapsed to JSON; body check before sending | B | P0 |
| Optional paging normalization per API (one envelope, `limit` + `cursor`) | B | P1 |
| Schema pack (`describe_schema`) | B | P0 |
| Optional lean input schemas | B | P1 |
| Optional sensitive-tool flag (warning, masking, explicit selection) | B | P1 |
| Optional multipart part descriptions (`parts`) | B | P1 |
| File inputs: one file object, `base64` and/or `path` (root-confined, size caps) | B→R | P0 |
| Exposure: plain | B | P0 |
| Exposure: discovery (search or catalog style), toolsets, hybrid; BM25 search | B | P1 |
| Confirmation for destructive tools (elicitation) | B | P1 |

## 5. Instances (target servers)
| Requirement | Set by | Prio |
|---|---|---|
| Single- or multi-instance build | B | P0 |
| Multi: `instance` required on every tool, enum of configured names, no default | B | P0 |
| Per instance: id, base URL, contract (`auto` or pinned), auth, TLS, timeout, read-only | R | P0 |
| Certificate material as `path` or `base64` (or references); per-instance in-memory truststore; `ca-only` / `ca-and-system` / `system`; hostname-only relaxation; no trust-all | R | P0 |
| Rotation without restart for `path` material; trust on first use with operator confirmation | R | P1 |
| `instances_list` tool (multi only) | B | P0 |
| Mixed contract versions across instances: union / namespaced / forbid | B | P1 |
| `auto` contract detection with `onFailure: useDefault \| refuse` | B→R | P1 |
| Error detail `full` (default) or `summary`; full body always in audit | R | P0 |
| Config reload without restart for instances, credentials, TLS | R | P1 |
| Extension SPI: custom tools, request enrichers, call filters (Java) | B | P1 |

## 6. Security
| Requirement | Set by | Prio |
|---|---|---|
| Inbound auth: none, static | B→R | P0 |
| Inbound OAuth 2.1 resource server per MCP authorization spec (RFC 9728 metadata, RFC 8707 audience, JWKS or RFC 7662 introspection, insufficient_scope) | B→R | P1 |
| Guardrail: no inbound auth on HTTP only on localhost unless `allowInsecure` | B→R | P0 |
| Downstream auth types supported by the build: basic, apiKey, bearer | B | P0 |
| OAuth 2 client credentials (RFC 6749 §4.4, token cache, refresh on 401), mTLS (combinable) | B | P1 |
| Per-user identity (token exchange, URL elicitation) | B | P2 |
| Secrets only as references (`env:`, `file:`); keychain | R | P0 |
| Secret fields marked in the config schema; plain values rejected unless `allowInlineSecrets` | R | P0 |
| Runtime config in YAML or JSON (by file extension) | R | P0 |
| Every config key settable by env var, including list items by index (`…_INSTANCES_0__…`); config file optional | R | P0 |
| Vault secret references | R | P2 |
| Redaction policy | B→R | P0 |
| Base-URL allowlist (prevents SSRF) | B→R | P0 |
| Unsatisfiable tools (missing scope): hide or explain | B | P1 |

## 7. Runtime behaviour
| Requirement | Set by | Prio |
|---|---|---|
| Transports: stdio, Streamable HTTP | B→R | P0 |
| Target address rule: complete `baseUrl`, https/443 and 80 defaults, contract base path appended when no path | R | P0 |
| One storage root for everything the server writes (env-settable) | R | P0 |
| Health endpoints (`/health/live`, `/health/ready`), JSON operational logs, never on stdout for stdio | B→R | P0 |
| Host, port, public URL, base path, TLS or proxy, trusted proxies, allowed origins, outbound proxy, management port | R | P0 |
| Timeouts | B→R | P0 |
| No automatic retries of upstream calls (agents decide); only the single protocol retries: token refresh on 401, certificate file rotation | B | P0 |
| Error codes and `isError` mapping (fixed) | B | P0 |
| Logging; stdio logs never on stdout | R | P0 |
| Health/readiness endpoints | B→R | P1 |

## 8. Audit & telemetry
| Requirement | Set by | Prio |
|---|---|---|
| Audit file: JSON Lines, path, rotation, retention | B→R | P0 |
| Argument handling: none, redacted, hashed | B→R | P0 |
| Extra audit sinks (S3-compatible, webhook, DB) | R | P2 |
| Telemetry: OTLP push, on/off (default off), endpoint, protocol, service name | B→R | P0 |
| Prometheus `/metrics` (HTTP only) | R | P1 |
| Generated OTel Collector example (incl. S3) in docs | B | P1 |

## 9. Support packs
| Requirement | Set by | Prio |
|---|---|---|
| Pack framework: descriptor, conditional registration, naming/selection/audit apply | B | P0 |
| Packs without external dependencies: schema, audit, diagnostics, health, recording, introspection | B | P0 |
| Jira (issue tracker), skills, agent feedback | B + R | P1 |
| Sidecar packs for non-Java runtimes | B | P2 |

## 10. Generation & HTTP client
| Requirement | Set by | Prio |
|---|---|---|
| Runtime mode; embedded specs; extension SPI (custom tools, enrichers, filters) | B | P0 |
| Built-in dynamic client (JDK HttpClient + Jackson) | B | P0 |
| Codegen mode: output location, extension style, class granularity | B | P1 |
| Typed client options: library, models, nullables, call style, version packages | B | P1 |
| Generated clients: one method per request content type listing only its own type; `Accept` from the operation's response types without JSON collapsing; must pass the wire tests | B | P1 |
| Both mode; separate client library; mock server from examples | B | P2 |

## 11. Builds & packages
| Requirement | Set by | Prio |
|---|---|---|
| Spring Boot jar | B | P0 |
| Desktop bundle (`.mcpb`), Docker image + compose | B | P1 |
| Key-pair signature of `SHA256SUMS` (offline verify); optional jar signing with a company certificate | B | P0 |
| Docker image: Temurin JRE default, base chosen per build; push to registry and/or `image.tar` | B | P1 |
| Native image; other languages | B | P2 |
| Generated config JSON Schema per variant; `--validate-config` | B | P0 |
| Docs for people (README, INSTALL, CONFIGURATION, TOOLS) | B | P0 |
| Docs for agents (AGENTS.md, SKILL.md, llms.txt), OPERATIONS, CHANGELOG | B | P1 |
| Builds: list, download (whole or per format), archive/restore, delete (released → archive first), retention | P | P0 |
| Package is self-contained: needs nothing from the platform, the vendor or the internet | B | P0 |

## 12. Operator tooling (inside the package)
| Requirement | Prio |
|---|---|
| `init` guided setup, `doctor`, `instance add/test/remove` | P0 |
| `client add` for Claude Code, Claude Desktop, Cursor, VS Code; `--print` fallback | P1 |

## 13. Platform API & agents
| Requirement | Set by | Prio |
|---|---|---|
| REST API covering everything the UI does | P | P1 |
| Platform MCP server, generated by the platform from its own API | P | P1 |
| Identities (person, agent, pipeline) with scopes | P | P1 |
| Agent guardrails: publish needs a person, allowed spec hosts, confirm destructive, quotas | P | P1 |
| Approvals queue | P | P1 |

## 14. MCP features beyond tools
| Requirement | Prio |
|---|---|
| Progress, cancellation, `listChanged` | P1 |
| Elicitation | P1 |
| Resources and resource templates from `GET` operations (`expose: resource`) | P1 |
| Prompts from templates in the definition, with argument completion | P1 |
| Tasks, sampling, MCP Apps | P2 |

## 15. CLI & pipelines
| Requirement | Prio |
|---|---|
| `validate`, `build`, `explain`, `preview`, `names accept` | P0 |
| `diff` (informational: added, removed, changed tools) with Markdown output for PR comments | P0 |
| Server version follows the API version (default contract) | P0 |
| `--frozen-lock`; CLI version pinning; reproducible (byte-identical) packages | P0 |
| `test` against mocks generated from specs, with user test cases | P1 |
| `sign` / `verify`, `init --from-spec`, `migrate` | P1 |
| `push` to a platform workspace; platform edits as PRs on linked git repos | P1 |
| `try` against a reachable target from a developer machine | P2 |

See [spec/cli.md](spec/cli.md) and [guides/ci-pipeline.md](guides/ci-pipeline.md).

## 16. Standards compatibility
| Requirement | Prio |
|---|---|
| MCP revision window (newest + last 18 months, ≥ 3), negotiated at initialize | P0 |
| Feature gating per MCP revision with defined fallbacks | P0 |
| OpenAPI feature support matrix; diagnostics for unsupported constructs | P0 |
| Runtime version pinning in the definition; "newer runtime available" notice | P1 |
| Legacy HTTP+SSE transport (opt-in) | P2 |
| Published compatibility matrix and deprecation policy | P0 |

See [spec/compatibility.md](spec/compatibility.md).

## Deferred

- Kubernetes manifests / Helm chart: on customer request.

- Breaking-change detection and gating between builds: the server version follows the API version, so tool changes come with API versions.

- Generated web UI / local admin page for delivered servers, and multi-tenant HTTP mode: out of scope for now; mcpgp generates MCP servers only.
- `loginToken` downstream auth type (custom login endpoints).

- Comparing variants in harness-lab. Revisit after the core is validated.
