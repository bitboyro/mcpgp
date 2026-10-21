# Roadmap

Milestones, in order. Each ends with something demonstrable. Scope per item: see
[../requirements.md](../requirements.md) (P0 unless noted).

| Milestone | Goal | Exit criteria |
|---|---|---|
| **M0 · Specification** (now) | Specs, ADRs, examples, process | This repo's docs reviewed; open questions below answered |
| **M1 · Schemas & conformance** | Make the specs machine-checkable | JSON Schemas for definition, runtime config, manifest, audit event; examples validate in CI; first conformance fixtures with expected manifests |
| **M2 · Compiler core (Java)** | Definition → manifest | Resolver (multi-file, root restriction, workspace refs), OpenAPI 2.0/3.0/3.1 parsing, selection, naming pipeline + lock, rules, schema shaping, validation; golden tests pass for all examples |
| **M3 · Java runtime + CLI** | Run a server from a manifest | stdio + HTTP, single/multi instance, basic/apiKey/bearer, audit file, audit + introspection packs; `mcpgp build/validate/explain`; operator `init/doctor/instance` |
| **M4 · Packaging** | Deliverable package | Spring Boot jar, generated config schema, human docs, checksums; CI check that the package makes no calls outside the customer's systems |
| **M5 · Platform API** ✓ | Builds as a service | [spec/platform-api.md](../spec/platform-api.md): file store, servers and versions, validate/preview, builds (download/archive/delete/release, retention, signing), duplicate/variant, library, identities (tokens, local accounts) with scopes, approvals, activity; REST API with its own OpenAPI. PostgreSQL store (✓, M9) |
| **M6 · Web UI** ✓ | Final-direction UI | Pages from the design canvas on top of the API, editing a server's settings on its page, sign-in with local accounts, OIDC and LDAP / Active Directory. Follow-ups in [design/ui.md](../design/ui.md) |
| **M7 · P1 wave** ✓ | Breadth | OTLP telemetry (moved from M3, 2026-10-10; ✓ with Prometheus /metrics), OAuth inbound (✓), OAuth CC + mTLS downstream (✓), discovery/hybrid/toolsets (✓), desktop bundle (✓), Docker (✓ files; image push/tar later), multi-API servers (✓ end to end), agent docs (✓), platform MCP server (✓, a package agents run with their own token), recording pack (✓) |
| **M8 · P1 completion** (in progress) | The rest of P1 | Resources (✓), prompts with completion and progress (✓); CLI `push`, `test`, `init --from-spec` (✓); UI follow-ups (operations on and off on Tools & rules (✓), Naming section (✓), try against the mock (✓), formats on Package & deliver (✓), the platform's own server first with Connect an agent (✓)); the extension SPI (✓); codegen mode (`generation.mode: codegen`, not started: refused by the compiler) |
| **M9 · Self-hosting** (in progress) | Run it at work | Platform image and Docker Compose (✓), PostgreSQL store with `copy-store` (✓), deployment settings: first admin from config, `/healthz`, secure cookies behind TLS, start-up warnings (✓), company sign-in wired into compose (✓, OIDC and LDAP existed), [deployment.md](../deployment.md) and the [how-to](../guides/self-hosting.md) (✓); publishing the image to Docker Hub (✓, `publish` workflow); next: passwords in the web UI (0.3.0) |
| **M10 · Release gate** (G1 in progress) | Releases blocked by an integration suite | [release-gate.md](release-gate.md): packaged servers driven by an independent MCP client against a validating API mock; performance measured against the previous release; a gate report attached to every release. G1 minimal gate → G5 performance budgets |

## Releases

| Version | Contents |
|---|---|
| **0.1.0** (released 2026-10-11) | M1–M8 and M9 so far: compiler, runtime, packaging, the CLI (zip, jar and image; `--all`, the version pin, `sign`), the platform and its web UI, the Docker images, Docker Compose, the PostgreSQL store |
| **0.2.0** (released 2026-10-12) | `setup` and `check` in the platform image (no repository on the server); shared notices: company policy text defined once in the library and put on every server (or chosen ones) in one step, from the UI, the API and the CLI |
| **0.2.1** (released 2026-10-12) | Start from your API's spec: a new server from an OpenAPI or Swagger file (JSON or YAML, several files that `$ref` each other, a folder or a zip, with missing files and broken references reported first); spec versions on Sources; HTTPS by the platform itself (self-signed or your certificate) from `setup`, checked by `check`; a clear sign-in message on a wrong address |
| **0.2.2** (released 2026-10-12) | Fix: specs whose schemas are recursive across files (a tree, a chain) compile; the recursive schema becomes a shared one |
| **0.2.3** (released 2026-10-12) | Fix: specs with schemas in separate files that refer to each other compiled in exponential time (every page slow); each file is now read once, and big shared schemas go to `$defs` |
| **0.2.4** (released 2026-10-12) | Operations: switches flip at once, filters by method and resource; each version compiled once for all its pages, and saving no longer invalidates every server's cached results |
| **0.2.5** (released 2026-10-12) | The result cache stays bounded (unsaved previews in memory only; each server's last 3 versions kept); gzip responses |
| **0.2.6** (released 2026-10-13) | Credentials from the MCP client (`fromRequest`): the client sends the user's credential for the API in a configured header, never stored; instances can list several credentials, tried in order (fallback); a switch on Connections |
| **0.2.7** (released 2026-10-13) | Per-operation security enforced (tools no credential can call hidden or explained; each call uses the credential that fits); a suffix for every tool and renaming one tool from the UI; audit defaults on Overview; `init --from-spec` copies referenced files; broken schema references are errors; Try starts its server early |
| **0.3.0** (released 2026-10-20) | Passwords in the web UI (change, set and unlock); per-user identity by token exchange; clashing tool names chosen on upload; contract detection for `auto` instances and a Connections page for the rule; spec versions from an address with Check for updates; `mcpgp diff`, `preview`, `try`, `migrate` and the platform's Changes page; the platform's own server with Connect an agent; MCP cancellation, list pagination, resource-template completion and real progress; Security & safety protections; output schemas; `mixedContracts` and `instances.max` enforced; settings that did nothing are refused; stdio parallel-answer fix |
| TODO, not yet scheduled | Marked TODO in the docs: `release.versionFrom` other than `contract`, `platform:` in the repository config; git sources on the platform (a spec from an address, with Check for updates: on `main`); a native CLI binary. (CLI `diff`, `preview`, `try`, `migrate` and `--no-color`, the platform's Changes page, and contract detection for `auto` instances: on `main`.) |

## Before 0.3.0: MCP 2026-07-28 and protocol utilities (added 2026-10-17)

Where we are today (checked in `runtime/.../mcp/`): **tools** ✓, **resources** ✓, **prompts** ✓ with
**completion** (`completion/complete`, prompt arguments with an enum only; no resource-template
completion), **progress** (a tick every second during an upstream call, only when the client sent a
`progressToken`; no real progress or `total`), **elicitation** form mode ✓ (confirm destructive calls).
Not implemented: cancellation, MCP-level pagination, tasks, URL-mode elicitation, MRTR, request-scoped
SSE as a chosen mode. SDK is 2.0.1; the SDK may not carry the 2026-07-28 revision yet (check first).

Order matters: 1 decides what the rest build on.

| # | Item | What | Notes |
|---|---|---|---|
| 1 | **MRTR (Multi Round-Trip Requests)** | 2026-07-28 breaking change: `roots/list`, `sampling/createMessage` and `elicitation/create` can no longer be server-initiated requests; the server answers the call with an "input required" result and the client retries with the answers. Our confirmations (`Confirmations.java`, `exchange.createElicitation`) use the old pattern. | Backward compatible, chosen by the negotiated revision (per request in stateless mode): 2026-07-28+ → MRTR; older revision whose client declares `elicitation` → the server-initiated request, as today (needs a stream, so not with `streaming: json`); neither → `CONFIRMATION_REQUIRED` + `confirm`. First check that the SDK exposes the negotiated version and can serve both revisions. See [compatibility.md](../spec/compatibility.md). |
| 2 | **Request-scoped streaming (stateless Streamable HTTP)** | A `POST` for a tool call is answered with an SSE stream of its own: `notifications/progress` down that stream, then the final JSON-RPC response on the same stream, then close. No global session stream, so any replica can serve any call. | **Customizable**: `transport.streaming: auto \| stream \| json` (default `auto`: stream only when the call has a `progressToken` or the tool is long-running; `json`: a single JSON response), and `transport.stateless: true` as the preferred mode wherever the client's revision allows. Per server in the definition, overridable per instance in the runtime config. Needs a proof with two replicas behind a load balancer. |
| 3 | **Cancellation** | `notifications/cancelled`: stop the upstream HTTP call (interrupt, close the connection), stop the progress ticker, send no response for the cancelled request; ignore cancels for unknown or finished requests (race); never cancel `initialize`. | ✓ on `main` (2026-10-17): stdio and HTTP; audited as `cancelled` / `CANCELLED`. Over HTTP the SDK still writes an `isError` result, which the client ignores. Also a call waiting for a confirmation; over stdio the cancelled request isn't answered. |
| 4 | **Real progress** | `progress` increasing on every notification, optional `total`, a `message`; for paged or multi-call tools, progress by page; keep the 1 s ticker as the fallback. | ✓ on `main` (2026-10-17): increasing values, `total` = the instance's timeout, a message naming the instance; the 1 s ticker stays (tools don't page yet, so there is no per-page progress). |
| 5 | **Pagination of lists** | Opaque `cursor` / `nextCursor` on `tools/list`, `resources/list`, `resources/templates/list`, `prompts/list`; page size configurable (`mcp.pageSize`, default: all). | ✓ on `main` (2026-10-17) as `server.pageSize` (stdio and HTTP; the transports cut the SDK's answer into pages). Not the tools-contract §10 envelope of API results, which stays. Cursors are opaque and bound to the list they were made for. |
| 6 | **URL-mode elicitation** | The standard way to sign each user in to the downstream API: the server returns a URL, the user authorizes there, the credential never passes through the client. Replaces the "feature unavailable; tool explains" row. | Ties to `fromRequest` and `tokenExchange`; the credential is stored per user and bound to their identity. Depends on 1. |
| 7 | **Tasks (experimental)** | `execution.taskSupport` per tool (`forbidden` / `optional` / `required`), task status lifecycle, `ttl`, results fetched later; each task bound to the caller's authorization context. | Read the security section first: a task must never be readable by another identity. Only for tools declared long-running; the synchronous call and progress stay the default. |
| 8 | **Completion, widened** | `completion/complete` for resource templates and for tool arguments with an enum or a lookup operation; `context.arguments` for dependent values. | ✓ on `main` (2026-10-17): resource templates (enum and boolean variables). Tool arguments are not in MCP's `completion/complete` (prompts and resource templates only), so no. |
| 9 | **Logging of all of this** | The server logs per call: transport mode used (`stream` or `json`), `progressToken` present, progress sent, cancelled (by whom, when), MRTR rounds (how many, which input), tasks created and finished, elicitation outcome (accepted, declined, cancelled), list pages served. | ✓ audit `mcp` object (transport, revision, progress, cancel reason) and INFO/DEBUG log lines, on `main` (2026-10-17); `logging/setLevel` checked. Also OTLP / Prometheus attributes and MCP log messages for upstream failures. Still to log when they exist: MRTR rounds, tasks. |

Also today ✓: the MCP support table is in [compatibility.md](../spec/compatibility.md) §1.2. Protocol-level behaviour is tested in the runtime's own tests, because the conformance traffic format (one call, one API request) has no cancel or paging step; fixtures for 1 and 2 follow when they exist. Left on purpose: `server.pageSize` is set at start (a reload keeps it, like telemetry), and API tool results don't page (the compiler still refuses `pagination`: its own feature).

## Next (agreed 2026-10-13, not scheduled into a release)

Releases happen when they're asked for; these land on `main` first.

| Item | What | State |
|---|---|---|
| Tool-name clashes on spec upload | When two operations would get the same tool name, the upload shows them with suggested names (from method and path) to accept or edit, or sets a rule for the server (`naming.collisions`); both are saved in the definition | on `main` |
| 0.3.0 passwords | See the release table | on `main` |
| UX and wording review | See Main priorities | planned |
| Per-user identity by token exchange | `tokenExchange`: RFC 8693 or Entra on-behalf-of, for a shared server whose users sign in with OAuth (auth.md §2.5) | on `main` |
| Store performance at scale | Activity searches filtered by the store (SQL and indexes on PostgreSQL) and bounded in time; the cache cleanup batched | on `main` |

### Backlog: stakeholder feedback (2026-10-20)

From an agent that built a server for a 570-operation API with the platform's MCP server and the CLI.
Fixed on `main` (Unreleased): `client add` in the packaged server, `instance trust` without a terminal
(`--fingerprint`), big answers in parts (`validate`, manifest, artifacts), `init --from-spec` on big
specs, the docs shipped with the CLI and the images, the MCP-native manifest (`tools-list`), the platform's read-only annotations. Not yet:

| # | Item | What | Notes |
|---|---|---|---|
| 2 | `instance trust` writes the config | It saves `certs/<id>.pem` but never sets `tls.ca.path` (by design, runtime-config §8: the file isn't edited); a `--write` that adds it the way `instance add` edits YAML | |
| 3 | Hot reload, said clearly | Which config keys reload live and which need a restart (`baseUrl` does, `files.path` doesn't); the `FILE_FORM_DISABLED` error names the keys (`files.allowPath`, …) that control it | docs: runtime-config §4.6; error: `FileInputs` |
| 6 | Output schema from a component | A rule that attaches a component schema to an operation's response when the spec has none (output schemas themselves: 0.3.0) | |
| — | Platform tool results | The manifest's `tools` is the last key; a response limit that leaves the useful part first | `fields` helps; consider keeping whole tools when over the limit |

Done from this list: credentials from the MCP client (0.2.6); per-operation security enforced, tool-name suffix and per-tool rename, audit defaults on Overview, `init --from-spec` with referenced files, broken schema references as errors, Try warm-up (0.2.7).

## Main priorities

Work that matters more than its place in the list suggests; scheduled between releases, not after
everything else.

**UX and wording review of the platform** (added 2026-10-11). A full pass over the web UI as someone
new to mcpgp would meet it, from the first sign-in to a released build delivered to a customer:

1. **Walk every page and flow** with a fresh installation and no prior context: where does a new
   user not know what to do next, what a control will do, or why something happened? Empty states,
   first-run guidance, order of steps, what is shown before it is needed.
2. **Inventory the texts** (labels, hints, notes, errors, confirmations) and mark the ones that only
   make sense with context the user may not have: internal terms (manifest, contract, variant, lock
   file, pinned version, exposure modes, packs, provenance, instance, `mcpgp://` references, select
   and exclude entries), spec section names, abbreviations, and hints that explain *how* without
   *why*.
3. **Fix**: plain-language copy, a short explanation where a term has to stay (inline, or a "what's
   this" that links to [concepts.md](../concepts.md)), the same word for the same thing across UI,
   API errors and docs, and a prioritized list of UX changes that need more than words.
4. **Check with people**: one or two colleagues who haven't seen the platform try the main flows;
   what they stumble on goes back into the list.

Outcome: a list of findings with the fixes made, and a wording guide in [../design/ui.md](../design/ui.md)
so new texts follow it.

Deferred: harness-lab comparison; other languages. (Per-user downstream identity: `fromRequest` and `tokenExchange`.)

Last, and only after they are thought through (2026-10-11): the issue-tracker, skills and agent-feedback
packs (whether they make sense at all), and paging normalization (APIs already page their own way;
the one real gap is paging links in response headers).

## Open questions (M0)

1. Skills pack: storage format and history (git, files, database)?
2. "Issue area" in the existing internal MCP server: local store or Jira mapping?
3. ~~Hosting of the platform itself: self-hosted only, or also a hosted offering?~~ Self-hosted,
   single-tenant for now ([ADR-0014](../decisions/0014-platform-self-hosted.md)).