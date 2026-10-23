# Feature matrix

Status: **source inventory**, revalidated against `main` at `20a8ca1` (2026-10-23).
This covers the generated server, compiler and CLI. Implementation status is based on source
inspection; test names identify relevant suites, not proof that every setting/value was exercised.
The final column describes required verification, not necessarily existing release-gate coverage.
Feature IDs (`F…`) are stable inventory identifiers. The proposed S6 `covers:` completeness check
in [release-gate.md](release-gate.md) is a design target, not an implemented completeness guarantee.

The platform (web UI, REST API, platform MCP server, library, approvals) is out of scope here; it
needs its own matrix.

**Set in:** **D** definition (build time, `*.mcpgp.yaml`, naming policy, rules) · **R** runtime config
at the customer (file, env vars, flags) · **C** the MCP client or the call · **B** the build command.

**Code:** ✅ implemented · ⚠️ partly · ❌ unsupported/deferred (explicit refusal where listed below). Checked by
reading the code, not by running it; "Tests today" lists the in-process Java tests that touch the
feature, which is not the same as covering every value.

## 1. Contracts, versions and instances

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F1 | Contract formats | D | `apis[].contracts[].spec`: Swagger 2.0 · OpenAPI 3.0 · 3.1, mixed per API | How parameters, bodies and security are read | ✅ | ContractLoaderTest, conformance | Same operations from each format give the same tools (S2) and requests (S3) |
| F2 | Overlays | D | `contracts[].overlays[]` | Spec edited before tools are made | ✅ | examples (orders 2.0 overlay) | Overlay text reaches descriptions; broken overlay → `OVERLAY_INVALID` |
| F3 | Several versions per API | D | `contracts[].version`, `default: true` | One package ships every version; default decides `auto` and the package version (2.0 → 2.0.0) | ✅ | ApiCallTest, ContractVersionsTest | Each version's requests use its own base path and shapes; package version follows the default |
| F4 | Pinned contract | R | `instances[].contract: "<version>"` | Instance uses that version; no detection; unknown version stops startup | ✅ | ApiCallTest, ContractVersionsTest | Pinned and `auto` instances side by side; a version not shipped refuses to start |
| F5 | **Contract detection** | D + R | `apis[].detect.{method GET·HEAD·POST, path, pattern, onFailure useDefault·refuse}`; `contract: auto` | Target asked at startup and reload which version it runs | ✅ runtime detection at startup/reload | ContractVersionsTest | Detected version used; fallback with `useDefault`; `refuse` disables only that instance; re-detect on reload |
| F6 | Per-version tool availability | D | (derived from contracts) | A tool missing in an instance's contract → `NOT_AVAILABLE_ON_INSTANCE`; arguments of another version → `INPUT_VALIDATION` | ✅ | ApiCallTest, ContractVersionsTest | Every tool × every version (S3) |
| F7 | Mixed versions across instances | D | `instances.mixedContracts`: `union` · `namespaced` · `forbid` | Union: one tool, per-version variants; namespaced: instance-prefixed tools; forbid: startup refuses mixed versions | ✅ all three modes | MixedContractsTest | All three values |
| F8 | Single or multi instance | D + R | `instances.mode`: `single` · `multi`; R `instance` · `instances[]` · `apis.<api>.instance(s)` | Multi: `instance` required on every tool (enum, no default), `instances_list` | ✅ | ApiCallTest, MultiApiTest | Both modes; `INSTANCE_REQUIRED` / `INSTANCE_UNKNOWN`; `instance` sent to a single build |
| F9 | Instance limit | D | `instances.max` | Startup refuses more instances than allowed | ✅ enforced by ConfigLoader | MixedContractsTest | Over the limit refuses to start |
| F10 | Several APIs per server | D + R | `apis[]`, `apis[].toolPrefix`; R `apis.<api>.instances` | Tools prefixed per API; instances per API; `<prefix>_instances_list` | ✅ | MultiApiTest | Two APIs with instances each; a call never reaches the other API's target |
| F11 | Target address | R | `instances[].baseUrl` | https/443, http/80 defaults; contract base path appended when the URL has no path | ✅ | ApiCallTest | With and without path; http warning |
| F12 | Read-only instance | R | `instances[].readOnly` | Writes → `NOT_AVAILABLE_ON_INSTANCE`, nothing sent | ✅ | ApiCallTest | Every write tool on a read-only instance |
| F13 | Timeouts | D + R | D `runtimeDefaults.timeouts.{defaultSeconds, maxSeconds}`; R `instances[].timeoutSeconds` | `UPSTREAM_TIMEOUT`; over the max refuses to start | ✅ | ApiCallTest (`slow`) | Default, per instance, over the max |
| F14 | Base-URL allowlist | D | `runtimeDefaults.baseUrlAllowlist` | Instances outside it refuse to start (SSRF guard) | ✅ | — | Inside, outside, wildcard forms |

## 2. Which tools exist

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F15 | Access preset | D | `select.access`: `read-only` · `read-write` · `all` | Which methods become tools (read-write drops DELETE) | ✅ | examples | Each preset's surface (S2); excluded tools unreachable |
| F16 | Include / exclude | D | `select.include` / `exclude` by `api`, `tag`, `method`, `path` (globs), `operationId`, `contract`, `extension`, `kind`, `tool` | Selected operations | ✅ | Selectors via examples | Every selector key, include then exclude precedence |
| F17 | Deprecated operations | D | `select.deprecated`: `hide` · `show` · `mark` | Hidden, listed, or listed with a deprecation note | ✅ | — | All three |
| F18 | Hidden per rule | D | `overrides.rules[].hidden` | Tool removed after naming | ✅ | — | Hidden tool unreachable directly and through discovery |
| F19a | Layered runtime tool policy | R | `tools.allow/deny`, `apis.<id>.tools`, `instance(s).tools`; `policy` command | Intersect permissions, filter catalogs, enforce selected instance, reload live | ✅ organization/deployment/API/instance/identity/session; see policy specs | ToolPolicyTest, ReloadTest | Inheritance, deny wins, no helper bypass, stale config rejected, stdio/HTTP |
| F19b | Policy management API | R | `server.management.enabled/keys/port`; `/management/v1/config`, `/preview`, `/apply` | Separate administrator credentials, bounded previews, revision checks, persist-before-publication | ✅ registered policy and ordinary-setting subset; see runtime-policy-management.md | PolicyManagementTest | Separate auth, port/origin restrictions, no preview side effects, stale/competing previews, persistence failures, secret-reference recovery |
| F19 | Unsatisfiable tools | D | `downstreamAuth.unsatisfiedTools`: `hide` · `error` | Tools no instance's credentials can call: hidden or explained | ✅ | UnsatisfiedToolsTest | Both values |

## 3. Names, descriptions and shapes

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F20 | Name source | D | naming `source` / `fallback`: `operationId` · `methodPath` · `tagSummary` | Where a name starts | ✅ | core, NamingApiTest | Each source on the kitchen-sink (S2 golden) |
| F21 | Name shaping | D | `normalize.{split, dropWords}`, `shape.{order object_verb·verb_object, case snake·camel·kebab, number singular·keep}`, `methodVerbs`, `vocabulary` | The final name | ✅ | NamingApiTest | Each value of `order`, `case`, `number`; PUT→replace, PATCH→update |
| F22 | Namespacing | D | `namespace.{server, suffix, perApi}` | Prefixes and suffixes | ✅ | — | Each key, with several APIs |
| F23 | Limits and collisions | D | `limits.profile` `strict`·`mcp`, `onTooLong` `shorten-then-hash`·`error`; `collisions` `error`·`method-path`·`suffix-number`·`suffix-contract` | Long names, clashing names | ✅ | — | Every value with a fixture that triggers it |
| F24 | Name lock | D + B | `mcp-names.lock`, `--frozen-lock`, `mcpgp names accept` | Renames fail the build until accepted | ✅ | NamesAcceptTest | Drift with and without `--frozen-lock` |
| F25 | Override rules | D | `overrides.rules[]`: `match` + `name`/`title`/`description` with `set`·`prepend`·`append`·`template`·`replace`; `params.<p>.description`; `annotations.*Hint` | Text and hints of matched tools | ✅ | DescriptionsTest | Each operation on each field; rule order |
| F26 | Notices | D | `descriptions.notices[].{text, position append·prepend, match}`, `descriptions.include` (library) | Text on many tools | ✅ | DescriptionsTest | Both positions; library include |
| F27 | Content type per tool | D | `requestBody.multiple` `split`·`prefer`, `prefer`, `suffixes`; rules `contentType`, `accept` | One tool per request type or one preferred; `Accept` from response types | ✅ | conformance multipart-split | `split` and `prefer`; every body type (S3) |
| F28 | Multipart parts | D | rules `parts.<name>.{description, contentType, secret, required}` | Part schema and wire content type | ✅ | conformance multipart-split | Each key; `secret` parts redacted in audit |
| F29 | Sensitive tools | D | rules `sensitive` | Warning in the description, masked in audit | ✅ | — | Masking in audit and recording |
| F30 | Lean input schemas | D | `schemas.input`: `full` · `lean` | Smaller schemas | ❌ `lean` refused with NOT_SUPPORTED_YET; `full` supported | UnsupportedSettingsTest | Refusal and full schemas |
| F31 | Output schemas | D, C | (from response schemas); revision ≥ 2025-06-18 | `outputSchema` + `structuredContent`, or JSON as text on 2025-03-26 | ✅ | — | Each revision |
| F32 | Response limit | D | `safety.responseLimitKb` | Truncation marker, then `RESPONSE_TOO_LARGE` | ✅ | — | Under, at, over the limit; JSON stays valid |

## 4. How tools are offered

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F33 | Exposure mode | D | `exposure.mode`: `plain` · `discovery` · `toolsets` · `hybrid` | Full list, discovery tools, switchable groups, or pinned + discovery | ✅ | DiscoveryAndPacksTest, ToolsetsTest | Every mode × single/multi instance × one/several APIs |
| F34 | Discovery style | D | `exposure.discoveryStyle`: `search` · `catalog` | search → describe → call, or list → schema → invoke | ✅ | DiscoveryAndPacksTest | Both; every operation findable and callable |
| F35 | Pinned tools | D | `exposure.pinned[]` (hybrid) | Listed directly beside discovery | ✅ | — | Pinned listed, others only through discovery |
| F36 | Search in plain mode | D | `exposure.plainSearch` | Adds `search_tools`, `describe_tool` | ✅ | — | On and off |
| F37 | Toolsets | D, C | `exposure.toolsets.<name>.{match, description}` (or one per tag); `toolset_enable` / `disable` per session | Session's tool list; `list_changed`; `TOOLSET_DISABLED` | ✅ | ToolsetsTest | Two sessions independent; notification sent |
| F38 | Resources | D | rules `expose: resource`; `resources.uriScheme` | GET operations as resources and templates | ✅ | ResourcesTest | Templates, unknown URI, scheme |
| F39 | Prompts | D, C | `prompts[].{arguments[].{required, enum}, messages}` | Prompts with argument completion | ✅ | PromptsTest | Completion, missing required, unknown prompt |

## 5. Calls: safety, files, errors

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F40 | Confirmation | D, C | `safety.confirmDestructive`; rules `confirm`; client elicitation capability; revision | Elicitation, or `CONFIRMATION_REQUIRED` + single-use 5-minute token bound to tool, instance, arguments, session | ✅ | ConfirmAndAuditTest, conformance confirm-destructive | On/off × elicitation accept/decline/absent × each revision; token misuse (S7) |
| F41 | File inputs | D + R | D `files.forms` `[base64, path]`; R `files.base64.{enabled, maxKb}`, `files.path.{enabled, root, maxKb}` | Accepted forms, size caps, root confinement | ✅ | ApiCallTest | Each form on/off; caps; escapes (S7) |
| F42 | Error detail | R | `errors.detail`: `full` · `summary` | How much of an upstream error body the model sees (all of it in audit) | ✅ | — | Both, for every error kind |
| F43 | Redaction | D | `runtimeDefaults.redaction.fields` | Fields masked in results, audit, logs | ✅ | — | Listed fields never leave unmasked |
| F44 | Error codes | — | fixed (audit-and-errors §3) | `isError` + `code` | ✅ all 15 used | various | Each code on each transport (S7) |

## 6. Credentials and TLS toward the API

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F45 | Auth types offered | D | `downstreamAuth.supported`: `basic`, `apiKey`, `bearer`, `oauth2ClientCredentials`, `mtls`, …; per-operation `security` | Which credential types the operator may configure; public operations send none | ✅ | conformance public-operation | A type the build doesn't support refuses to start |
| F46 | Static credentials | R | `auth.type` `basic` (`username`, `password`), `bearer` (`token`), `apiKey` (`value`; header/query from the contract) | Header or query sent | ✅ | ApiCallTest | Each type; secrets never in results |
| F47 | OAuth client credentials | R | `tokenUrl`, `clientId`, `clientSecret`, `clientAuth` `basic`·`post`, `scopes`, `audience`, `resource` | Token fetched, cached, shared, refreshed once on 401 | ✅ | TlsAndOAuthTest | Both `clientAuth`; one token under concurrency; one retry |
| F48 | Credential from the request | D + R | D `downstreamAuth.fromRequest.header`; R `auth[]` entry `fromRequest` with `header`, `as` | HTTP clients send a per-user credential, forwarded and never stored | ✅ | CredentialsFromRequestTest | Present, absent (falls to next entry), stdio (ignored) |
| F49 | Token exchange (per user) | D + R | D `downstreamAuth.tokenExchange`, `identity` `service-account`·`per-user`; R `flavor` `rfc8693`·`entra-obo` | Signed-in user's token exchanged for one for the API | ✅ | TokenExchangeTest | Both flavors; exchange failure; cache per user |
| F50 | Credential lists | R | `auth: [ … ]` tried in order | First usable entry wins | ✅ | CredentialsFromRequestTest | Order permutations |
| F51 | Instance TLS | R | `tls.ca.{path, base64}`, `tls.trust` `ca-only`·`ca-and-system`·`system`, `clientCert`/`clientKey` (`path`·`base64`), `password`, `insecureSkipHostnameVerify` | Own truststore, mTLS, hostname-only relaxation | ✅ | TlsAndOAuthTest | Each trust mode with a matching and a wrong CA; mTLS; rotation of `path` files |
| F52 | Trust on first use | R | `<server> instance trust <id>` (`--base64`, `--yes`) | Saves the target's certificate after confirmation | ✅ | ServerCommandsTest | Then a call succeeds |

## 7. The server itself

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F53 | Transport | D + R | D `runtimeDefaults.transports.{allowed, default}`; R `server.transport` `stdio`·`http` | stdio (logs on stderr) or Streamable HTTP | ✅ | StdioServerTest, StdioProcessTest, HttpServerTest | Every suite on both |
| F54 | MCP revisions | C | client's `protocolVersion`: `2025-03-26` · `2025-06-18` · `2025-11-25` | Negotiation and feature fallbacks | ✅ | — | Every case on every revision |
| F55 | HTTP settings | R | `server.{host, port, publicUrl, basePath, allowedOrigins, management.port}` | Where it listens, Origin check, separate health/metrics port | ✅ | HttpServerTest | Origin allowed/blocked; base path; management port |
| F56 | Inbound HTTPS | R | `server.tls.{cert, key}` | Direct TLS object refused; reverse proxy supported | ❌ direct HTTPS unsupported; see §10 | UnsupportedServerSettingsTest | Actionable refusal |
| F57 | Proxies | R | `server.trustProxy`, `server.outboundProxy` | Reserved fields, refused when present | ❌ unsupported; see §10 | UnsupportedServerSettingsTest | Actionable refusal |
| F58 | Inbound auth | D + R | D `runtimeDefaults.inboundAuth.{allowed, default}`; R `server.auth.mode` `none`·`static` (`keys[]`)·`oauth` (`issuer`, `jwksUri`, `introspection`, `scopes.<s>.{access, tools}`), `allowInsecure` | Who may call; scopes limit tools; no auth off-localhost refused | ✅ | OAuthTest, HttpServerTest | Every mode; scope → tool mapping; the guardrail |
| F59 | Health | — | `/health/live`, `/health/ready` | Readiness | ✅ | HttpServerTest | Ready only when instances are |
| F60 | Audit | D + R | `audit.file.{enabled, path, rotate daily·hourly·size:N, retainDays}`, `audit.arguments` `none`·`redacted`·`hashed` | JSON Lines events | ✅ | ConfirmAndAuditTest | Every argument mode; rotation (test clock C3) |
| F61 | Telemetry | D + R | `telemetry.{enabled, exporter otlp·prometheus, otlp.{endpoint, protocol grpc·http/protobuf, headers}, serviceName, signals}` | Spans and metrics out; off by default | ✅ | TelemetryTest | Each exporter and protocol; off sends nothing |
| F62 | Logging | R | `logging.{level, target file·stderr}` | JSON log lines; never stdout on stdio | ✅ | StdioProcessTest | Both targets |
| F63 | Config sources | R | YAML or JSON file, `.env`, env vars (indexed lists), flags; precedence | Same settings, any source | ✅ | ConfigLoaderTest, ConfigFileTest | The same profile three ways gives identical results |
| F64 | Secrets | R | `${env:…}`, `${file:…}`; `allowInlineSecrets` | Plain secrets refused unless allowed | ✅ | ConfigLoaderTest | Each form; refusal names the key, not the value |
| F65 | Storage root | R | `storage.root` | Where everything the server writes goes | ✅ | — | Nothing written elsewhere |
| F66 | Startup checks | R | (runtime-config §7) | Refuses to start, naming the key | ✅ | ConfigLoaderTest | Every check (S7) |
| F67 | Reload | R | file watch, `SIGHUP`, `<server> reload` | Instances, credentials, TLS, contract choice, errors, files change live; a bad config keeps the old one | ✅ | ReloadTest | Each trigger; restart-only keys warn |
| F68 | Operator commands | R | `init`, `doctor` (`--quick`), `instance list·test·trust·add·remove`, `client add` (Claude Code, Claude Desktop, Cursor, VS Code; `--print`, `--file`), `reload` | Setup without editing files | ✅ | ServerCommandsTest, SetupCommandsTest | Each command followed by a call |

## 8. Packs and extensions

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F69 | Packs | D + R | `packs`: `schema`, `audit`, `diagnostics`, `health`, `introspection`, `recording` | Extra built-in tools | ✅ | DiscoveryAndPacksTest, RecordingTest | Each pack on and off |
| F70 | Issue-tracker, skills, feedback packs | D | `packs` | — | ❌ deferred (roadmap) | — | — |
| F71 | Extensions | D | `extensions.{artifacts[].path, customTools[], enrichers[].{handler, order, match}, callFilters[].{…}}` | Custom tools; headers added to requests; calls blocked | ✅ | ExtensionsTest | Each kind; order; a failing extension |

## 9. Build and packages

| ID | Feature | Set in | Settings → values | What changes | Code | Tests today | Gate must verify |
|---|---|---|---|---|---|---|---|
| F72 | Package formats | D | `build.targets`: `jar` · `mcpb` · `docker` (docker needs HTTP) | What the zip contains | ✅ | PackageTest, DeliveryTest | Every format launched as the customer would (S0) |
| F73 | Generated docs | D | `build.docs`: people, agents | README, INSTALL, CONFIGURATION, TOOLS; AGENTS.md, llms.txt | ✅ | PackageTest | TOOLS matches the served surface |
| F74 | Signing | D + B | `build.sign.key` (minisign) | `SHA256SUMS.minisig` | ✅ | SigningTest, SelfContainedCheckTest | Verify with the real `minisign` |
| F75 | Jar signing | D | `build.jarSigning.{keystore, alias, password}` | Signed jar | ❌ explicitly refused; see §10 | UnsupportedSettingsTest | Actionable refusal |
| F76 | Docker image | D | `build.image.base`; `registry`, `push`, `tar` | Dockerfile base; enabled publishing options refused | ✅ Dockerfile/base; publishing options unsupported | — | Image builds from the package |
| F77 | Generation mode | D | `generation.mode` `runtime`·`codegen`·`both`; `codegen.{output, extend, classes}` | Codegen | ❌ `codegen`/`both` refused with NOT_SUPPORTED_YET; `runtime` supported | — | After implementing |
| F78 | Reproducible builds | B | `SOURCE_DATE_EPOCH`, pinned CLI | Byte-identical packages | ✅ | conformance compiler | Build twice, compare (precondition) |
| F79 | Variants and multi-file | D | `extends`, `$ref`, `include`, `$remove`, workspace references | One definition from many files | ✅ | MergerTest, ResolverSecurityTest, ExamplesResolveTest | Variant surface = base + overrides |
| F80 | CLI | B | `init`, `validate`, `build`, `explain`, `names`, `test`, `push`, `verify`, `diff`, `preview`, `try`, `migrate` | — | ✅ | MainTest, TestCommandTest, PushTest | Gate uses `build`, `verify`, `test` |

## 10. Unsupported settings and values

Reserved format fields are not working capabilities. The compiler refuses unsupported definitions
with `NOT_SUPPORTED_YET`; runtime startup refuses unsupported listener/proxy settings. These are
not silently ignored. See `core/.../Unsupported.java` and `runtime/.../config/ConfigLoader.java`.

| ID | Setting | Current behavior / supported alternative |
|---|---|---|
| F30 | `schemas.input: lean` | Refused; `full` supported |
| — | `apis[].pagination` object | Refused; use upstream API pagination parameters |
| F56 | `server.tls` object | Refused; use a TLS reverse proxy (`tls: proxy`). Platform HTTPS is a separate supported feature |
| F57 | `server.trustProxy`, `server.outboundProxy` | Presence refused; direct upstream calls only |
| F75 | `build.jarSigning` object | Refused; package checksum signing via `build.sign` supported |
| F76 | `build.image.registry`, enabled `push` / `tar` | Non-null/enabled values refused (`false` accepted); Dockerfile/base supported |
| F77 | `generation.mode: codegen · both` | Refused; `runtime` supported |
| — | CLI workspace `platform` configuration | Remote workspace references refused |
| — | CLI `release.versionFrom` other than `contract` | Refused; explicit `--version` supported |
| F1 | OpenAPI 3.2 | Refused; Swagger 2.0, OpenAPI 3.0/3.1 supported |
| F64 | Secret providers other than `env` / `file` | Unsupported; do not treat arbitrary vault references as implemented |

Contract detection (F5), mixed-contract policies (F7) and instance limits (F9) are implemented;
they no longer belong in this list. Future issue-tracker/skills/feedback packs (F70) remain deferred.

## 11. Covering "all configurations"

All combinations are not testable: the settings above multiply to billions. The release-gate design proposes three
layers instead; the inventory is not evidence that those completeness checks exist today.

1. **Every value once.** Each value of each setting in this matrix should have at least one case where it
   changes an observable result (the surface, a request, a response, an audit line). The planned S6 check would derive this
   from `covers: [F33=toolsets, …]`.
2. **Pairs that interact.** Settings known to affect each other should be tested pairwise (every value of
   one with every value of the other):

| Interacts | With | Why |
|---|---|---|
| F33 exposure mode | F8 instance mode, F10 several APIs, F69 packs | Discovery and toolsets must carry `instance` and API prefixes; packs listed directly |
| F40 confirmation | F54 revision, client elicitation, F53 transport | Elicitation on new revisions and HTTP sessions; token fallback elsewhere |
| F5 detection / F4 pinning | F7 mixed contracts, F67 reload, F6 availability | The tool list depends on which versions are live |
| F12 read-only | F15 access preset, F40 confirmation | Which writes exist and which are refused |
| F46–F50 credentials | F53 transport, F58 inbound auth | `fromRequest` and token exchange need HTTP and a signed-in user |
| F42 error detail | F43 redaction, F60 audit arguments | What leaks where |
| F63 config sources | everything in R | Same result from YAML, JSON and env only |
| F72 formats | F53 transport | Docker is HTTP-only; `.mcpb` is stdio |

3. **High-risk combinations in full.** Small sets where a bug would be expensive should get every
   combination: confirmation × elicitation × revision × transport (3 × 3 × 3 × 2 = 54); credential
   types × transport × inbound auth mode.

**Knowing when something breaks:** every row's "Gate must verify" should become cases tagged with its ID.
A change that breaks a feature should fail those cases. The proposed S6 check would fail a change that adds a setting without a row here, or
a value without a case. Until implemented, reviewers must verify these gaps explicitly.
