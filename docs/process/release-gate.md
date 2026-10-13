# Release gate (plan)

Status: **plan, not started**: picked up after 0.1.0 · Roadmap: **M9 · Release gate**

A release is published only when the packages we ship pass an integration suite run by an MCP
client we did not write, over real transports, against an API mock that checks every request.
0.1.0 ships without it; this document is what we build next, grounded in the code as of `5ac148d`.

## 1. Rule

Every test ends in a real MCP client call that travels through the **packaged** server to the API:

```
 independent MCP client ──► packaged server (jar | .mcpb | docker) ──► validating API mock
        │                              │                                       │
  expect: MCP result          observe: audit, OTLP, logs            upstream: requests + 0 violations
```

- A step is one MCP request (`initialize`, `tools/list`, `tools/call`, `resources/read`,
  `prompts/get`, `completion/complete`, …). It asserts what the client got (`expect`), what the API
  received (`upstream`: `none` or an exact, ordered list, always with zero spec violations) and the
  side effects of that call (`observe`).
- Operator actions (CLI, config edits, certificate rotation, restart) only appear as `do:` before a
  call: `instance add` alone is not a test, `instance add` followed by a call that reaches the new
  instance is.
- Startup refusals end in a call too: the client's `initialize` fails, the process exits with a
  message naming the key, and the mock received nothing.
- The only checks that are not calls are **package preconditions**, run once per package before the
  suite: checksums, `minisign` signature, byte-identical rebuild, `--validate-config` on the example
  configs, compiler diagnostics cases.

Pass: 0 failed steps, 0 mock violations, coverage complete (§6), no retries, no quarantine, and
performance within budget (§8). Every run produces the gate report (§9).

## 2. What exists today (code analysis)

### 2.1 Reusable

| Piece | Where | Use in the gate |
|---|---|---|
| Conformance data format (`case.yaml`, `traffic.yaml`, matchers `startsWith`/`matches`/`absent`/`any`) | [spec/conformance.md](../spec/conformance.md), `schemas/.../conformance-traffic.schema.json` | Grows into traffic v2 (§4); existing cases stay valid |
| Java traffic runner | `runtime/src/test/.../conformance/ConformanceTrafficTest.java` | Stays as the fast in-process runner for PRs |
| Spec-driven mock | `packager/.../trial/SpecMock.java` (258 lines) | Base of the validating mock (§3) |
| Scripted mock with multipart parsing | `runtime/src/test/.../call/MockTarget.java` (153 lines) | Folded into the same mock |
| Package check (strace, minisign, checksums) | `cli/src/test/.../SelfContainedCheckTest.java`, `MCPGP_PACKAGE_CHECK=1` in `ci.yml` | Becomes a package precondition |
| Process smoke | `runtime/src/test/.../StdioProcessTest.java` | Superseded by S0 |
| `mcpgp test` and `tool-tests.schema.json` | `cli/.../Main.java` (`test`), `packager/.../trial/MockRun.java` | Customer-facing format; translated to traffic v2 internally so one engine runs both |
| Test certificates | `runtime/src/test/.../TestCerts.java` | Generator for `conformance/fixtures/certs/` |
| Example definitions | `examples/platform/{orders,orders-readonly,ops-assistant}` | Fixtures as they are |

### 2.2 Gaps the gate closes

| Today | Consequence | Gate |
|---|---|---|
| The Java runner calls `McpServerFactory.call(null, …)`: no transport, no session (`exchange` is null) | Session binding of confirmation tokens, elicitation, HTTP sessions and error replies on the wire (#41) are never tested end to end | Real transports, real sessions |
| The Java runner supports `tools/call` only, at most one upstream request per step | No resources, prompts, completion, progress, cancellation; OAuth token + call sequences can't be asserted | Every MCP method; ordered upstream lists |
| `SpecMock` answers the first 2xx, keeps the last 20 requests, cuts bodies at 4 KB, flags only unknown routes (404) | A wrong parameter, header, body or multipart part passes | Validating mock with a full journal |
| Three mocks: `SpecMock`, `MockTarget`, harness-lab's `mock_http.py` | Behaviour drifts between them | One mock, shared by `mcpgp test`, conformance and the gate |
| `MockRun` is a hand-written JSON-RPC client with `protocolVersion` fixed to `2025-11-25` and empty capabilities | Same authors on both sides; older revisions and capability fallbacks untested | Independent client (official TypeScript SDK) |
| Packages are built in tests, never tested as customers run them | `.mcpb` launch (`java -jar ${__dirname}/server/<server>.jar`, env from `user_config`) and the Docker files are not exercised | Launchers per format |
| 4 conformance cases (2 negative); 1 of 38 diagnostic codes has a case | Most behaviour is covered only by in-process unit tests | Suites S0–S7 |
| `ci.yml` only; no release workflow | Nothing can block a release | `release.yml` with the gate in front of publishing |
| No requirement IDs in `requirements.md`; no `AGENTS.md` | "Tests added" is a checkbox | Coverage rule (§10) |

### 2.3 Facts the plan depends on

- **MCP revisions claimed:** `ManifestWriter.MCP_REVISIONS` = `2025-03-26`, `2025-06-18`,
  `2025-11-25`. Every case runs on all three unless it says otherwise; `2025-03-26` exercises the
  fallbacks of [compatibility.md](../spec/compatibility.md) §1.2 (no `structuredContent`, no
  elicitation → `CONFIRMATION_REQUIRED` + token).
- **Transports:** `StdioTransport`, and Streamable HTTP through `RevisionLimitedTransport`
  (MCP Java SDK 2.0.1).
- **Formats** (`Packager.TARGETS`): `jar`, `mcpb` (`Bundle`), `docker` (`Container`: Dockerfile and
  compose only; the image is not built, and the target needs the HTTP transport). The gate builds the
  image from the package exactly as INSTALL tells the customer to.
- **Operator commands** (`ServerCommands`, `SetupCommands`): `init`, `doctor`, `reload`,
  `instance list|test|trust|add|remove`, `client add`.
- **Reload** (`Reloader`): file watch, `SIGHUP` where available (not Windows), or a request file left
  by `<server> reload`; a config that doesn't load keeps the running one. Transport, address, port,
  inbound auth and storage root need a restart.
- **Upstream** (`Upstream`): redirects never followed; connect timeout `min(timeout, 30 s)`;
  timeouts map to `UPSTREAM_UNREACHABLE` / `UPSTREAM_TIMEOUT`.
- **Errors:** all 15 codes of [audit-and-errors.md](../spec/audit-and-errors.md) §3 appear in the
  runtime. File problems are `INPUT_VALIDATION` with a reason prefix in the message
  (`FILE_TOO_LARGE`, `FILE_OUTSIDE_ROOT`, `FILE_NOT_FOUND`, `FILE_FORM_DISABLED`, `FILE_INVALID`,
  `FILE_CONTENT_TYPE`).
- **Confirmation** (`Confirmations`): single-use token, 5 minutes, bound to tool, instance,
  arguments and session; the clock is injectable only in Java.
- **Telemetry** (`Telemetry`): OTLP over HTTP and gRPC, Prometheus `/metrics`.

### 2.4 Changes needed in mcpgp itself

| # | Change | Why |
|---|---|---|
| C1 | Standalone validating mock (`mcpgp mock`, §3) replacing `SpecMock` and `MockTarget` | One mock everywhere |
| C2 | Structured `reason` next to `code` in `structuredContent` for `INPUT_VALIDATION` | Tests (and agents) shouldn't parse message prefixes |
| C3 | A test clock for packaged servers, e.g. `MCPGP_TEST_CLOCK_OFFSET`, honoured only with `--test-mode` and refused otherwise | Token expiry and audit rotation can't be tested against a package without waiting |
| C4 | `mcpgp build --docker-image` (or a documented script) producing the image the customer would build | Docker launcher; also closes "image push/tar later" in the roadmap |
| C5 | Requirement IDs in `requirements.md`; changelog entries reference them | Coverage rule (§10) |
| C6 | Performance requirements (`R17.x`) in `requirements.md`: startup, call overhead, concurrency, memory, sizes | Budgets for S8 (§8) |

## 3. The validating mock

Built from `SpecMock` and `MockTarget`; one process per server under test; loopback only.

- **Validation, independent of `ApiModel`**: every request is checked against the operation in the
  contract with a validator mcpgp's compiler doesn't use (Atlassian `swagger-request-validator`,
  which covers Swagger 2.0 and OpenAPI 3.x): path, query (all styles), headers, cookies,
  `Content-Type`, `Accept`, body schema, multipart parts, security scheme. If the compiler misreads a
  spec, a mock reading it the same way would agree; this one doesn't.
- **Replies**: the documented 2xx by default (example, else built from the schema); per-step
  scenarios: any documented status, `problem+json`, headers (`Retry-After`, `Location`, `Link`),
  `delay`, `timeout`, `stall-body`, `reset`, `oversize`, `wrong-type`, `invalid-json`, `redirect`.
- **State** (optional, `seed:`): an in-memory store keyed by resource path, so writes are visible to
  later reads.
- **Control API** under `/__mock/`: `requests` (full journal, no size cap), `violations`,
  `scenario`, `state`, `reset`, `coverage` (operations and statuses hit).
- **TLS mode** with certificates from `fixtures/certs/` (server cert, wrong CA, expired, wrong host,
  client-cert required).
- **Fakes beside it**: an identity provider (JWKS, introspection, token endpoint for client
  credentials, RFC 9728 metadata) and an OTLP sink (HTTP and gRPC) that records spans and metrics.

## 4. Case format: traffic v2

A superset of today's `conformance-traffic.schema.json`; existing cases stay valid.

```yaml
fixture: kitchen-sink-oas31          # shared fixture, or definition/ next to the case as today
profile: multi-3                     # runtime config profile
client: { revision: all-claimed, elicitation: absent }   # accept | decline | absent
tags: [smoke]                        # smoke: also on macOS and Windows
covers: [R4.24, R5.3]                # requirement IDs (§10)
mock: { seed: notes-basic }

steps:
  - name: delete asks first and sends nothing
    call: tools/call
    params: { name: notes_note_delete, arguments: { instance: eu, path: { id: n-1 } } }
    upstream: none
    expect: { isError: true, code: CONFIRMATION_REQUIRED }
    observe: { audit: [{ event: tool.call, outcome: confirmation_required }] }

  - name: a read-only instance refuses writes
    do: [{ cli: [instance, add, ro, --base-url, "{{mock.url}}", --read-only] }]
    call: tools/call
    params: { name: notes_note_update, arguments: { instance: ro, path: { id: n-1 }, body: { title: x } } }
    upstream: none
    expect: { isError: true, code: NOT_AVAILABLE_ON_INSTANCE }

  - name: 429 is passed on and never retried
    mock: { respond: { status: 429, headers: { Retry-After: "3" }, problem: { title: slow down } } }
    call: tools/call
    params: { name: notes_note_get, arguments: { instance: eu, path: { id: n-1 } } }
    upstream: [{ method: GET, path: /v1/notes/n-1 }]
    expect: { isError: true, code: UPSTREAM_4XX }
```

| Field | Meaning |
|---|---|
| `do` | Before the call: `cli`, `config` (patch, then wait for the reload), `env`, `rotate-cert`, `restart`, `clock` (C3) |
| `mock` | This step's reply or fault |
| `call`, `params` | The MCP request |
| `upstream` | `none`, or ordered matchers; the count is exact; zero violations always |
| `expect` | The MCP result or JSON-RPC error |
| `observe` | `audit`, `otlp`, `log`, `notifications` (progress, `list_changed`), within this step |

The runner rejects any `code` not listed in audit-and-errors.md §3.

## 5. Runner

TypeScript, in `conformance/runner/ts/`, on the official `@modelcontextprotocol/sdk` client.

```
orchestrator
 ├─ plan      matrix row (os × format × transport × revision) × fixture × profile → servers → cases
 ├─ build     mcpgp build per fixture × format, cached by definition hash
 ├─ precheck  checksums · minisign · byte-identical rebuild · --validate-config · diagnostics cases
 ├─ fakes     validating mock (reset between cases) · identity provider · OTLP sink
 ├─ launch    jar:    java -jar … (stdio | http)
 │            mcpb:   unpack, run manifest.json's mcp_config with user_config filled in
 │            docker: build the image as INSTALL says, run with the profile mounted (Linux)
 ├─ client    one session per case; capabilities and revision from the case
 ├─ assert    expect · upstream (journal + violations) · observe (audit tail, OTLP sink, stderr)
 ├─ measure   S8: timings from client and mock, process memory, sizes (§8)
 └─ report    gate report HTML + JSON (§9) · JUnit XML · failure bundles
```

- One server per (fixture, profile, matrix row); cases with `restart` or config changes get their own.
- Fixtures in parallel, cases in sequence on a server.
- No sleeps: waits are on `/health/ready`, the journal or a notification, each with a timeout.
- Failure bundle: MCP transcript, mock journal and violations, server stderr, audit slice, and a
  one-line repro (`npx mcpgp-gate --fixture … --case … --format mcpb --transport stdio`).

## 6. Suites

Run in this order; a failing suite stops the ones after it.

| Suite | Content | Written | Size |
|---|---|---|---|
| **S0 Package** | Each format starts, answers `initialize` and `tools/list`; stdout carries only JSON-RPC on stdio; `.mcpb` as Claude Desktop runs it; Docker as documented | by hand | ~10 per format |
| **S1 Protocol** | Negotiation on each claimed revision and refusal with the list; per-revision fallbacks; HTTP sessions (`Mcp-Session-Id`, DELETE, an error reply keeps the session); ping; cancellation; concurrent calls; `tools/list` paging | by hand | ~40 |
| **S2 Surface** | Each fixture's `tools/list`, `resources/list`, `prompts/list` equal `golden/surface.json`; every schema is valid JSON Schema; discovery finds every operation; toolsets switch and send `list_changed` | generated | 1 per fixture × exposure mode |
| **S3 Operations** | Every tool of every fixture: valid arguments from `inputSchema` → 0 violations and correct mapping; one-constraint mutations → `INPUT_VALIDATION`, `upstream: none`; every documented status → its code; every fault → its code | generated, seeded | thousands |
| **S4 Behaviour** | By area: content types, files, results and truncation, paging, confirmation, exposure, instances, contract versions, downstream auth, inbound auth, TLS, extensions, packs, resources and prompts, progress, elicitation, completion | by hand | ~150–250 |
| **S5 Operator** | Config sources (YAML, JSON, env only give the same results), reload, rotation, `init`/`doctor`/`instance`/`client add`, audit modes, telemetry, health | by hand | ~50 |
| **S7 Negative** | §7 | by hand | ~120 |
| **S8 Performance** | §8: startup, call overhead, concurrency, memory, sizes, against budgets and the previous release | scenarios | ~20 scenarios |
| **S6 Coverage** | Not tests: merges `covers:` with mock coverage; fails on any gap (§10) | computed | — |

S3 writes any failing generated step out as a traffic v2 file for `cases/regressions/`.

### 6.1 Fixtures

The fixtures and profiles exist to cover [feature-matrix.md](feature-matrix.md): every feature (`F…`),
every value of its settings, and the interacting pairs listed there (§11).

| Fixture | Turns on |
|---|---|
| `kitchen-sink-{swagger2,oas30,oas31}` | Every method, parameter style, body type (JSON, polymorphic, recursive, form, multipart, binary, text), response kind (201 + `Location`, 202, 204, binary, CSV, every error status), pagination style, auth type, hostile inputs |
| `naming` | Vocabulary, method verbs, prefixes, limits, collisions, explicit names, aliases |
| `rules` | `set`/`prepend`/`append`/`replace`/`template`, notices, per-tool edits; one rule that changes a parameter |
| `selection` | Include/exclude by tag, path, method, operationId; hidden deprecated; the three access presets |
| `exposure-{discovery-search,discovery-catalog,toolsets,hybrid}` | Each exposure mode |
| `multi-api`, `multi-version`, `variant` | Prefixes; versions with different operations, `auto` detection, union/namespaced/forbid; `extends` |
| `shape` | Lean schemas, sensitive tools, multipart `parts`, `outputSchema` |
| `packs-all`, `extension`, `resources-prompts` | Every pack; a test extension jar (custom tool, enricher, filter); `expose: resource`, prompts with completion |
| `orders`, `ops-assistant` | The shipped examples |

### 6.2 Profiles

`single` · `multi-3` (`eu` plain; `us` TLS with a test CA and OAuth client credentials; `ro`
read-only with a short timeout) · `env-only` and `json` (same as `multi-3`) · `inbound-oauth` ·
`mtls` · `telemetry-on` · `audit-{none,redacted,hashed}` · `strict` (summary errors, no inline
secrets, allowlist).

### 6.3 Matrix

| Tier | Runs on | Scope | Budget |
|---|---|---|---|
| T1 | Linux | All suites × {jar, mcpb, docker} × {stdio, http} × 3 revisions; S3 in full on jar, sampled on the others | ≤ 20 min, sharded |
| T2 | macOS, Windows | `smoke` cases of S0, S1, S4 × {jar, mcpb} × stdio × newest revision | ≤ 10 min |
| PR | Linux | Java in-process runner + T1 for jar over stdio | ≤ 8 min |
| Perf | Linux, one fixed runner size | S8, previous and new release interleaved on the same machine (§8.3) | ≤ 25 min |

## 7. Negative tests (S7)

Every negative step checks four things: the documented failure (code, and `isError` vs JSON-RPC
error vs HTTP status); the right upstream (`none`, or exactly one request, no retries); no leak (no
stack trace, planted canary secret, full upstream body or internal path in the result, stderr or
OTLP); and the aftermath (the next call in the session works, the audit file has the failure).

| Who misbehaves | Cases |
|---|---|
| Client / model | Unknown or excluded tool (by name and through discovery); wrong type, missing field, enum out of range, extra property, over limits; arguments valid for another contract version; missing, unknown or superfluous `instance`; operation not on the instance; write on a read-only instance; disabled toolset; confirmation without token, wrong, reused, other session, other arguments, expired (C3), elicitation declined or cancelled; bad base64, oversize, both file forms, path outside the root, symlink escape, missing file; unknown resource URI, template without a variable, unknown prompt or argument |
| API | Each documented 4xx with and without `problem+json`; 401/403; 401 with client credentials (one refresh, one retry, then `AUTH_DOWNSTREAM`); 429; undocumented statuses; 5xx; timeout, stalled body, reset; closed port, unresolvable host, TLS failures; invalid JSON, wrong content type, schema mismatch, empty body; oversize (truncation, then `RESPONSE_TOO_LARGE`); redirect (not followed); `auto` detection failing with `useDefault` and `refuse` |
| Operator | Every startup check of [runtime-config.md](../spec/runtime-config.md) §7; reload with a broken config (old one keeps serving); rotation to a broken certificate; `instance add` with a bad URL or duplicate id |
| Attacker (`security`, never skipped) | `../`, `%2e%2e`, `/`, `?`, `#` in path parameters; CRLF in headers and query; absolute or `//` URLs in parameters; `baseUrl` outside the allowlist; canary secrets in config, env, upstream bodies and headers; inbound tokens missing, expired, wrong audience or issuer, bad signature, missing scope, `alg: none`; disallowed `Origin`; forged `Mcp-Session-Id`; oversized and deeply nested messages; 1,000 concurrent calls; an extension filter blocking and an extension throwing |
| Protocol | Call before `initialize`; second `initialize`; unsupported revision; malformed JSON-RPC; unknown method; cancelling a finished or unknown request; stdin closed mid-call |

Compiler diagnostics never reach a server: each of the 38 codes in `core/.../diag/Codes.java` gets an
`expected/diagnostics.json` case, run as a package precondition.

## 8. Performance (S8)

Nothing in the specs sets a performance target today; the runtime records `mcpgp.tool.duration`
(`Telemetry`) and audit events carry `latencyMs`, but no budget exists. S8 measures the packaged
server the same way the other suites test it: through MCP calls, against the mock. Budgets become
requirements (`R17.x`, C6) so they are covered like any other.

### 8.1 What is measured

The mock answers instantly and stamps when each request arrives and its reply leaves, so a call's
time splits into **mcpgp overhead** (client-side duration minus time spent in the mock) and upstream
time. Overhead is what mcpgp controls and what budgets apply to.

| Metric | Scenario | Per |
|---|---|---|
| **Startup** | Process start → `/health/ready` (http) or the first `initialize` reply (stdio), cold, 10 runs | format, transport, fixture size |
| **Time to first tool call** | Start → `initialize` → `tools/list` → first `tools/call` answered | format, transport |
| **Surface cost** | `tools/list` size in bytes and in tokens (one fixed tokenizer), and its latency | fixture, exposure mode |
| **Call overhead** | p50 / p95 / p99 over 1,000 warmed calls: small GET, JSON POST, multipart 1 MB and 10 MB, binary download 10 MB, a response truncated at the limit, a discovery `invoke` | transport, revision |
| **Concurrency** | 1, 8, 32, 128 concurrent sessions over Streamable HTTP for 60 s: throughput, p99 overhead, error count (must be 0) | profile |
| **Token sharing** | 64 concurrent calls with OAuth client credentials on a cold cache: token requests (must be 1) and the wait added | — |
| **Memory** | RSS and heap at idle, after warm-up, after the 128-session run; growth over 10,000 calls (leak check) | format |
| **Reload** | Config change → first call using the new instance | file watch, `reload` command |
| **Cost of options** | Overhead with audit `hashed` vs `none`, telemetry on vs off, an extension enricher on vs off | — |
| **Sizes** | jar, `.mcpb`, zip, Docker image | format |
| **Build** | `mcpgp build` time per fixture | fixture |

Memory comes from the process (RSS read by the runner; `docker stats` for the image) and from JVM
metrics on `/metrics` where the profile enables them.

### 8.2 Budgets

Two kinds, both recorded in `conformance/perf/budgets.yaml` with the requirement ID they cover:

- **Absolute budgets**, generous, so a slow CI machine doesn't trip them (starting values to confirm
  with the first measurements: startup ≤ 3 s for the jar, p95 overhead ≤ 15 ms for a small GET, 0
  errors at 128 sessions, heap growth ≤ 5 % over 10,000 calls, one token request under concurrency).
- **Regression budgets** against the previous release, measured on the same machine in the same job
  (§8.3): fail when a metric is worse by more than its threshold (proposed: 10 % for overhead and
  startup, 5 % for package sizes and surface tokens) **and** the 95 % interval of the difference
  excludes zero. A difference inside the noise is reported as a tie and passes.

### 8.3 Method

- **A/B on one machine.** Hosted runners vary by more than the regressions worth catching, so the
  perf job downloads the previous release's packages and runs both versions interleaved
  (old, new, old, new …) on the same runner; only differences within that job count.
- Fixed runner size; warm-up excluded; each scenario repeated (startup 10 runs, latency 1,000 calls
  × 3 rounds); medians and percentiles with bootstrap intervals; the random seed and machine details
  recorded.
- Load comes from the same TypeScript client as the other suites (several SDK sessions), so it
  measures what MCP clients actually see.
- **Advisory first.** S8 reports from G5 on; absolute budgets block after the first release that
  sets them; regression budgets block after three releases with stable numbers.

## 9. Gate report

Every gate run, on a PR or a release, produces one report.

| Output | For | Where |
|---|---|---|
| `gate-report.html` | People: one self-contained page (inline CSS and SVG charts, no external requests), readable offline | CI workflow artifact on every run; release asset on every release |
| `gate-report.json` | Machines: every result, measurement and environment detail; schema `gate-report.schema.json` in `schemas/` | Same places; the next release reads the previous one from here |
| PR comment | Reviewers: verdict, failures, coverage gaps and perf deltas in a few lines, linking the full report | The PR |
| JUnit XML | The GitHub checks UI | Workflow artifact |

**Contents of the HTML report:**

1. **Verdict**: pass or fail, and why: the blocking findings first.
2. **Run**: version, commit, previous release compared against, matrix, runner, JVM, seeds, duration.
3. **Correctness**: a suite × matrix-row grid (S0–S7); every failure with its repro command and a
   link to its failure bundle.
4. **Coverage** (S6): requirements covered, gaps, the new requirement IDs of this release and whether
   their cases were red on the previous release (§10).
5. **Surface**: tools added, removed and changed against the previous release (from S2), and the
   surface cost per exposure mode.
6. **Performance** (S8): a table of every metric against its budget and the previous release, with
   the delta, its interval, and the verdict (better, tie, worse, over budget). Charts: startup per
   format, overhead percentiles per call kind, throughput and p99 against concurrency, memory over the
   10,000-call run.
7. **Trend**: the main numbers across past releases, read from their `gate-report.json` assets.

The release job attaches the report to the GitHub release, and the release notes link to it, so
customers can see what a version was tested against.

## 10. Keeping it current

**Rule: no feature or fix ships unless Gate A has cases for it, at least one positive and one
negative, that pass on this release and fail on the previous one.**

1. **Requirement IDs** (C5): every row of [requirements.md](../requirements.md) gets an ID; cases
   declare `covers:`; changelog entries end with `[R…]`; `coverage.yaml` is generated from
   requirements.md.
2. **Every PR** (`gate-coverage` job in `ci.yml`): a PR touching `docs/spec/**`, `*/src/main/**` or
   the changelog's Unreleased section needs a positive and a negative case for each ID in its
   changelog entry, or the `no-behaviour-change` label with a reason approved by a person. Golden
   files need a CODEOWNERS review; the S2 surface diff is posted on the PR.
3. **Every release** (`release.yml`, before the gate): each ID in the release's changelog section is
   covered on each transport; every case added since the last tag **fails on the previous release's
   packages** (or is tagged `existing-behaviour` with a reason); every S2 golden diff since the last
   tag is explained by a changelog entry; every requirement marked shipped has cases. The report goes
   into the release notes.
4. **Release steps** ([development.md](development.md) §7) become: freeze `main` → coverage report →
   close gaps → changelog → tag RC → gate → tag release.
5. **Coding agents**: an `AGENTS.md` (and a `CLAUDE.md` importing it) states the rule; cases are
   written from the spec before the code; a `gate-cases` skill drafts positive and S7 cases for a
   requirement ID and proves them red on the previous release; a local hook runs
   `gate coverage --changed`; CI enforces it for people and agents alike.

## 11. Work packages

Estimates: engineer-days by hand / at the current agent-assisted pace. The second is bounded by
review, not by writing.

| # | Package | Main tasks | Depends on | By hand | Assisted |
|---|---|---|---|---|---|
| W1 | Validating mock | Merge `SpecMock` + `MockTarget`; independent validator; control API; scenarios; state; TLS mode; `mcpgp mock` command; switch `mcpgp test` and the Java runner to it | — | 5–8 | 0.5–1 |
| W2 | Traffic v2 | Schema; matchers; ordered upstream; `do`/`observe`; code allow-list; migrate the 4 cases | — | 2–3 | 0.5 |
| W3 | Runner core | Orchestrator, jar launcher (stdio, http), client sessions, assertions, JUnit, failure bundles, repro CLI | W1, W2 | 8–12 | 1–2 |
| W4 | More launchers | `.mcpb` (from `Bundle`'s manifest), Docker (C4) | W3 | 3–5 | 0.5–1 |
| W5 | Fakes | Identity provider, OTLP sink, certificate set | W1 | 4–6 | 0.5–1 |
| W6 | Fixtures | Kitchen-sink ×3, ~15 definitions, ~9 profiles, seeds | W1 | 8–12 | 1–2 |
| W7 | S2 Surface | Golden files with `-Dmcpgp.updateExpected`-style regeneration; discovery and toolsets checks | W3, W6 | 2–3 | 0.5 |
| W8 | S3 Operations | Argument generator, one-constraint mutations, status and fault sweeps, regression write-out | W3, W6 | 6–10 | 1–2 |
| W9 | Hand-written suites | S0, S1, S4, S5, S7 (~400 cases); C2, C3 | W3–W6 | 20–40 | 3–6 |
| W10 | Coverage and rule | C5, `coverage.yaml`, S6, `gate-coverage`, red-on-previous, `AGENTS.md`, skill | W2 | 4–6 | 1 |
| W11 | CI | `release.yml`, sharding, caching, T2 runners, publishing behind the gate | W3 | 4–6 | 1–2 |
| W12 | Gate report | `gate-report.schema.json`; JSON writer; self-contained HTML (verdict, correctness, coverage, surface); PR comment; release asset; trend from past releases | W3 | 4–6 | 1 |
| W13 | Performance | Mock timestamps; S8 scenarios; load sessions; process memory; A/B against the previous release; bootstrap intervals; `budgets.yaml`; C6; perf section and charts of the report | W3, W4, W12 | 6–10 | 1–2 |
| | **Total** | | | **~80–125** | **~14–23 days** |
| W14 | Fixing what it finds | Open-ended | | +30–50 % | +30–50 % |

Not compressible: reviewing expected values and golden files, the spec questions S7 raises, CI
round-trips on macOS, Windows and Docker, and flake hunting.

## 12. Milestones

| Milestone | Packages | Exit criterion |
|---|---|---|
| **G1 · Minimal gate** | W1, W2, W3, W11 (Linux, jar), W12 (verdict and correctness), S3 on `kitchen-sink-oas31` | The 4 existing cases pass through both runners; `release.yml` publishes only after the gate; the first RC is gated and its report is attached to the release |
| **G2 · Breadth** | W4, W6, W7, W8 | All formats and fixtures; S2 golden files reviewed |
| **G3 · Depth** | W5, W9 | S1, S4, S5, S7 green; P0 requirements covered |
| **G4 · Discipline** | W10, T2 runners | S6 blocking; the PR and release coverage checks on |
| **G5 · Performance** | W13, the rest of W12 | S8 in the report against the previous release; absolute budgets blocking after the first release that records them; regression budgets blocking after three stable releases |

## 13. Decisions needed before G1

1. Client language for the runner: TypeScript (recommended, the SDK most clients use) or Python.
2. Release candidates: gate on `v*-rc*` tags and publish the final tag only if its RC passed on the
   same commit, or gate the final tag directly.
3. GitHub-hosted macOS and Windows runners for T2.
4. S3 depth per tool and release (proposed: 5 valid sets + every one-constraint mutation).
5. Whether `mcpgp test` keeps its simple `tool-tests` format for customers (proposed: yes, translated
   to traffic v2 internally).
6. Before G5: the perf runner size (hosted or self-hosted), the tokenizer used for surface cost, the
   starting budgets (§8.2), and whether the report is linked from customer-facing release notes.

## 14. Out of scope

- The platform (web UI, REST API, platform MCP server, library, approvals): its own suite.
- **Agent tests (Option B)** with [harness-lab](https://github.com/bitboyro/harness-lab), later. Changes it
  needs, recorded so they aren't lost: an Anthropic provider (only OpenAI exists); a gate command
  with exit codes (`harness run` always exits 0) and regression against a baseline; a native arm that
  uses the server's tools as served (its own discovery arm wraps tools in its own meta-tools);
  isolation, state snapshots and writes for field packs when the target is a local mock;
  `tools/list_changed` and elicitation in its MCP client; checking that `legacy` negotiates mcpgp's
  revisions; stdio (optional); a pinned release wheel. Packs reuse the same `covers:` IDs.
