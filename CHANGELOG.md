# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com),
versions follow [SemVer](https://semver.org).

## Unreleased

### Added
- What changed between builds: `mcpgp diff` (against the last release tag, a tag or commit, a
  manifest or a release folder; text, Markdown for PR comments, or JSON; `--fail-on-breaking`, exit
  code 6) and the platform's **Changes** page (any two versions, by default since the last release;
  linked from a build's Release). Tools are matched by operation, so renames show as renames; inputs
  and outputs field by field; breaking changes first.
- `mcpgp preview` (the tools the agent sees, and what the customer configures), `mcpgp try` (a tool
  call against a real target, a runtime config with credentials, or a mock), `mcpgp migrate` (one
  format so far: nothing to change), `--no-color`.
- The platform's own MCP server is the first server people see: `mcpgp-platform`, made by the
  platform at its first start from the project in its release and given a new version by each upgrade
  (`system: true`; read, try, build and make variants of it; changes are `409 SYSTEM_SERVER`).
  `platformServer.enabled: false` archives it. **Connect an agent** (on its card, its Overview and API &
  agents) creates an agent and its first token, shows the setup for Claude Code, Claude Desktop, Cursor
  and VS Code, and says when the agent first called; people who aren't admins are asked to have an
  admin create the agent. On a platform with no servers of its own, All servers opens there.
- `GET /tokens` says when each token was `lastUsed`.
- Contract detection for `contract: auto` instances (runtime-config §4.4), which the spec described but
  the runtime didn't do: an older system got the default contract's requests. The server now asks
  each target which version it runs, at startup and on reload (starting waits up to 2 s; a slower
  target answers in the background and its calls wait): under each contract's base path in
  turn with `detect.underBasePath` (the instance's new `prefer` contract or the default first, then the
  newest), reading `detect.field` (a JSON pointer) or `detect.header`. Per-caller credentials are used
  when the target wants them. With `onFailure: refuse` an instance is unavailable
  (`INSTANCE_UNAVAILABLE`, asked again at most once a minute). `instances_list` shows the detected
  version, `doctor` warns when a pinned instance's target runs another version, and each result is an
  `instance.contract` audit event. The orders example's rule reads `/version` under each base path.
- Connections: **Which version a customer's server runs**, for servers that ship two or more
  versions of an API. It sets, changes or turns off the detection rule: the path, whether each
  version answers under its own base path, where the version is (a JSON field, a header or the
  whole answer), the pattern, and what happens when it can't be found. Before, the page only showed
  a rule written in the definition by hand.

- A spec version **from an address**: on Sources, **Add a spec version** can fetch the spec from
  where the API publishes it instead of uploading it, and a version fetched that way has **Check for
  updates**, which fetches it again and shows what agents would notice (the Changes page's list)
  before anything is saved; the same spec makes no version. The platform fetches only from hosts an
  administrator lists in `specHosts` (none by default), on request only; a host's credential is a
  reference, never stored with a server or shown. API: `POST /servers/{id}/contracts?url=…` and
  `?source=true&version=…`; the address is recorded as `apis[].contracts[].source`.

- **Security & safety**, a page per server on the platform, with every protection on by default and
  each one adjustable or off:
  - **large answers**: the server never downloads more than 4× `responseLimitKb` (a huge answer
    could exhaust its memory before), and a JSON answer over the limit keeps whole items of its
    largest list, stays structured and says how many it left out (`safety.cleanTruncation`);
  - **what agents send**: tool-call arguments and HTTP requests are size-capped and long text values
    refused, before anything is sent (`safety.inputLimits`, `INPUT_TOO_LARGE`, `413`);
  - **calls to each target server**: at most 8 at once (more wait) and 120 per minute
    (`safety.rateLimits`, `RATE_LIMITED` with how long to wait); a target's `429 Retry-After` reaches
    the agent. Operators can change them per instance (`instances[].rateLimits`).
  The page lists the protections that have no switch. `docs/security.md` maps them to OWASP.
- **Output schemas**: tools describe their result (`outputSchema`), from the API spec's success
  answer, kept loose (no `required`, extra fields allowed) because real answers vary; a result that
  differs is logged, never refused. On by default; off with `schemas.output: false`, the Security &
  safety page, or `mcpgp build --no-output-schemas`.
- JSON lists and plain values are now structured results too (`{ items }`, `{ value }`), not text only.

### Fixed
- stdio: answers finishing at the same moment could be dropped (the MCP Java SDK's stdio transport
  hands each message over in a single attempt, which fails when another is being handed over), so a
  client sending parallel tool calls never got some of the answers. Messages now go out one at a time.
- Parameters: cookie values are encoded (a `;` could add a cookie), and a header value with a line
  break is refused with `INPUT_VALIDATION` instead of an internal error.
- Settings the docs described but nothing did are now refused, naming each one, instead of being
  ignored: in the definition `generation.mode: codegen`/`both`, `schemas.input: lean`,
  `apis[].pagination`, `build.jarSigning`, `build.image.registry`/`push`/`tar` (the compiler's
  `NOT_SUPPORTED_YET`); in the runtime config `server.tls` with a certificate, `server.trustProxy`
  and `server.outboundProxy` (at startup). The specs mark them "not supported yet"; the roadmap no
  longer lists codegen as done.
- Activity searches read the whole log when few entries matched, and agent quotas read it on every
  save or build: searches now go to the store as a query (PostgreSQL filters in SQL, with indexes on
  target and actor) and stop at their time bound. The cache cleanup deletes in one batch.

### Added
- Passwords in the web UI: Change password on the Account page (the name in the sidebar); Set
  password and Unlock on a person in API & agents, for admins. An admin's password can be temporary
  (`mustChange`): the person replaces it at the next sign-in, and the platform answers
  `PASSWORD_CHANGE_REQUIRED` to anything else until then. `POST /identities/{id}/unlock` ends a lock
  after wrong passwords. Company accounts show none of this.
- Calling the API as the signed-in user (`tokenExchange`, auth.md §2.5): on a shared server with
  OAuth sign-in, each call exchanges the user's token at the identity provider (RFC 8693, or Entra
  on-behalf-of) for a token issued for the API. Opt-in per server (`downstreamAuth.tokenExchange`, a
  switch on Connections); exchanged tokens stay in memory per user until shortly before they expire;
  the user's token goes only to the token endpoint. A `.default` scope (Entra) isn't judged against an
  operation's scopes.
- Clashing tool names, the user's choice: when two operations would get the same tool name (often the
  same operationId twice), the spec upload shows each one with a suggested name from its method and
  path, to accept or edit (saved as a rule per operation), or to make the server's rule
  (`naming.collisions: method-path`, new); the Naming page sets the rule too. The compiler's error
  suggests the name.

## 0.2.7 — 2026-10-13

### Added
- Tool names from the UI: a suffix for every API tool's name (`naming.namespace.suffix`, e.g.
  `orders_customer_get_v2`) next to the server prefix on Naming, and a Name field in a tool's editor
  on Tools & rules that renames that one tool (saved in its rule, `name: { set: … }`).
- Try starts its server when the panel opens (`POST …/try/warm`), so the first call doesn't wait
  for it.
- Audit defaults in the definition, checked: `runtimeDefaults.audit` (`file.retainDays`, `file.rotate`,
  `file.enabled`, `arguments`) is typed, so a misspelt key is an error instead of being ignored; the
  platform's Overview sets how long the audit log is kept and how arguments are recorded.
- `mcpgp init --from-spec` copies the files a local spec refers to (followed from file to file), and
  says which referenced files are missing before writing anything.

### Fixed
- A schema reference to a shared schema that isn't there (`#/definitions/Entyr`) was accepted and
  became an empty placeholder in tools; it's now a `REF_UNRESOLVED` error, in requests and responses.
- Tools an instance's credentials can't call were listed, and the API refused the call
  (auth.md §2.2 wasn't enforced). Now a tool no instance can call is hidden (with
  `unsatisfiedTools: hide`, the default; the startup log says which and why) or kept with a clear
  error (`error`); calls for an instance that can't call a tool answer `AUTH_DOWNSTREAM` naming what's
  missing, without calling the API; and each call uses the first of the instance's credentials that
  fits the operation. Instances without credentials, `fromRequest`, and OAuth clients whose config
  names no scopes aren't judged. The manifest says what each security scheme is (`schemes`).

## 0.2.6 — 2026-10-13

### Added
- Credentials from the MCP client (`fromRequest`, auth.md §2.4): when the definition allows it
  (`downstreamAuth.fromRequest`), the client sends the credential for the API in a configured request
  header, set in the client's configuration from the user's environment, so the agent never sees it.
  The server reads it per request for one instance, sends it only to that instance's address, and
  never stores or logs it (it's masked in results, errors and recordings; the audit records only
  `upstream.auth: fromRequest`). Each instance reads its own header (default
  `X-<Server>[-<Api>][-<Instance>]-Authorization`). An instance's `auth` can now be a list tried in
  order, so a service account takes over when the header is missing. HTTP only.

## 0.2.5 — 2026-10-12

### Fixed
- The store's cache of compiled results grew without limit: every preview of an unsaved change kept its
  manifest (several MB for a big server), and nothing was ever removed, which made the store, and with
  it every page, slower over time. Previews of unsaved changes are now kept in memory only, and the
  cache keeps each server's last 3 versions (older ones compile again when opened; archived servers'
  results go). Existing caches are cleared at start.
- Responses are compressed (gzip) for JSON and the web app: a big server's manifest goes from MBs
  to tens of KB over the network.

## 0.2.4 — 2026-10-12

### Added
- Operations: filter by method (several at once) and by resource (the path's first named segment, e.g.
  `/partners`); "Turn off these" acts on what the filters show.

### Fixed
- Turning operations on and off was slow: every switch waited for the server and each change compiled
  the server two or three times. Switches now flip at once (the preview follows), a version is compiled
  once for all its pages, and saving a server no longer throws away its own and other servers' cached
  results (they're keyed on what a server actually references). Compiles over 2 s are logged.

## 0.2.3 — 2026-10-12

### Fixed
- Specs with schemas in separate files that refer to each other compiled in exponential time: a
  140-operation spec took minutes, and every page of its server with it. Schemas in other files are
  now shared schemas, read once, and a tool's schema puts big shared schemas in `$defs` instead of
  copying them five levels deep. The same spec compiles in about a second.

## 0.2.2 — 2026-10-12

### Fixed
- Specs whose schemas are recursive across files (a criteria tree whose children are criteria, a
  certificate whose parent is a certificate) failed with `REFERENCE_CYCLE`. Such a schema now becomes
  a shared schema and the tools reach it through `$defs`, as when it's written in the spec itself.

## 0.2.1 — 2026-10-12

Start from your API's spec in the web UI, and HTTPS without a proxy.

### Added
- HTTPS served by the platform itself, off unless configured: `http.tls` with a certificate and key
  (PEM) or a PKCS12 keystore, reloaded when the files change. `setup` asks who does HTTPS for an
  `https://` address: the platform with a self-signed certificate (made with the JRE's keytool), the
  platform with your files, or your own proxy (the default with `--yes`, as before); `setup --tls
  self-signed|files|proxy` switches an existing folder. `check` verifies the certificate: key, host,
  expiry. The image's health check works over HTTP and HTTPS.
- Sign-in: a plain `http://` page of a platform whose address is `https://` is told which address to
  open, instead of a session cookie the browser drops (`409 WRONG_ADDRESS`); the sign-in page says so
  too when it isn't on the platform's address.
- The upload of a spec also reports references to parts that aren't there (`#/components/schemas/…`
  inside the spec, or in a referenced file), not only missing files.
- Start from your API's spec on the platform: All servers → **New server** takes an OpenAPI 3.0 / 3.1
  or Swagger 2.0 file in JSON or YAML, several files that `$ref` each other, a folder or a zip. It
  finds the main file, follows every `$ref`, says which referenced files are missing (and which
  references are URLs) before anything is created, places files picked without their folders where
  the spec expects them, and builds the project like `mcpgp init --from-spec`.
  `POST /api/v1/servers/from-spec`.
- Sources: **API specs** lists each API's spec versions; **Add a spec version** and **Replace** take an
  uploaded spec the same way and make the server's next version. `POST /api/v1/servers/{id}/contracts`.

## 0.2.0 — 2026-10-12

Shared notices (company policy text once, on every server), and running the platform image without
the repository: `setup` writes the Docker Compose folder, `check` says what would fail.

### Added
- Platform image: `setup` writes everything to run it with Docker Compose into a folder (compose
  files pointing at the image, `platform.yaml`, `.env`, generated secrets and a signing key), asking
  or from flags, never overwriting; `setup --upgrade` moves the folder to a newer image. `check` says
  what would fail at start (secrets, the database, the identity provider, start-up warnings) without
  starting. No clone of the repository on the server any more.
- The platform reads its config path from `MCPGP_PLATFORM_CONFIG` when `--config` isn't given; the
  image sets it, so its commands (`check`, `init`, …) find the mounted `platform.yaml`.
- Shared notices: company policy text written once and carried by many servers. A library item of
  kind `notices` (`mcpgp://<workspace>/notices/<name>@<version>`; for the CLI, `notices:` in
  `mcpgp-workspace.yaml`), included with `descriptions.include`; a server's own notice with the same
  id replaces a shared one. Provenance and `mcpgp explain` name the set a notice comes from.
- Platform: shared notices in the Shared library (create, edit, used by), and
  `POST /library/notices/{name}/apply` to add a set to every server, or chosen ones, or move them to a
  new version in one step; on a server's Overview, the sets it uses, with **Use @N** and **Remove**.

## 0.1.0 — 2026-10-11

The first release: the compiler, the runtime and packaging, the CLI, and the self-hosted platform
with its web UI, published as the Docker image `mcpgp-platform` (see
[docs/guides/self-hosting.md](docs/guides/self-hosting.md)).

### Known limitations
- Not in this release (marked TODO in the docs): `mcpgp diff`, `preview`, `try`, `migrate`,
  `--no-color`; `release.versionFrom` other than `contract` and `platform:` in `.mcpgp/config.yaml`;
  git and URL sources on the platform; a native CLI binary.
- The web UI has no password page yet: local accounts' passwords are set through the API (0.2.0).
- One platform process per store; builds run one at a time.

### Added
- The CLI as a download: `mcpgp-cli-<version>.zip` (launchers `bin/mcpgp`, `bin/mcpgp.cmd`, Java 21+)
  and `mcpgp-cli-<version>.jar` on the GitHub release, and the CLI image `mcpgp-cli` for CI.
- CLI: `.mcpgp/config.yaml` is used: `--all` on `validate`, `build` and `test` (its `servers`), the
  version pin (`cli`, exit code 5, `--ignore-version`), `workspace` and `out` as defaults, `--config`.
- CLI: `mcpgp sign <dir>` signs a release directory (`SHA256SUMS`, `SHA256SUMS.sig`); `mcpgp verify`
  checks a signed directory as well as a package; `--help` on every command.
- Publishing: the platform and CLI images for `linux/amd64` and `linux/arm64` on Docker Hub, with an
  SBOM and build provenance: `:main` and `:sha-<commit>` from `main`, `:X.Y.Z`, `:X.Y` and `:latest`
  from release tags, and branch images on request (Run workflow). Release tags also make the GitHub
  release with the CLI files and `SHA256SUMS`.
- The pipeline example works as it is: its config points at the example servers, and the orders
  server's test cases live in `examples/platform/orders/tests/`.
- Self-hosting: the platform image (`deploy/Dockerfile`, non-root, health check) and Docker Compose
  files for the file store, PostgreSQL, package signing and company sign-in; `deploy/smoke-test.py`
  checks an installation end to end. Docs: [docs/deployment.md](docs/deployment.md) and
  [docs/guides/self-hosting.md](docs/guides/self-hosting.md).
- Platform: the PostgreSQL store (`storage.type: postgres`), next to the file store, which stays the
  default; one platform process per store; `copy-store --from <dir>` moves a file store's data into it.
- Platform: the first administrator from the config (`admin.name`, `admin.password` as a reference),
  `GET /healthz`, `MCPGP_PLATFORM_STORAGE__*` and `MCPGP_PLATFORM_ADMIN__*` variables, `deployment:`
  warnings at start, and `Secure` session cookies whenever `http.publicUrl` is `https://`.

- `core` module, the compiler: multi-file definition resolution, Swagger 2.0 / OpenAPI 3.0 / 3.1
  loading with overlays, selection, rules, the naming pipeline with `mcp-names.lock`, grouped input
  schemas, and a deterministic manifest validated against its schema.
- `cli` module: `mcpgp validate` and `mcpgp build --manifest-only`.
- Conformance runner compares produced manifests with `expected/manifest.json`.
- `runtime` module (Spring Boot, MCP Java SDK): loads the embedded manifest and the operator config
  (YAML/JSON file, `.env`, environment variables, flags, secret references, startup checks) and
  serves the tool list over stdio with the `instance` enum filled in; `instances_list`.
- Runtime: API tool calls: instance and contract selection, read-only instances, input validation
  (schema and contract), request building (parameter styles, JSON, form, multipart with per-part
  content types, binary bodies, body check before sending), file inputs (base64 / path within a
  root, size caps), basic / bearer / apiKey credentials, response and error mapping with redaction
  and `errors.detail`.
- Conformance traffic runner (Java): runs `traffic.yaml` steps against a mock target.
- Runtime: per-instance TLS (own truststore, `ca-only` / `ca-and-system` / `system`, hostname-only
  relaxation, rotation of `path` material), client certificates (PKCS#12 or PEM + PKCS#8 key),
  OAuth 2 client credentials (token cache, one token request at a time, single retry on 401).
- Compiler: `security: []` (public operations) is kept in the manifest; the runtime sends no
  credentials for them. Conformance case `auth/public-operation`.
- Runtime: confirmation of destructive tools (elicitation, or a single-use 5-minute token bound to
  tool, instance, arguments and session); the compiler adds the optional `confirm` input. Conformance
  case `safety/confirm-destructive`.
- Runtime: audit file (JSON Lines): `server.start/stop`, `session.start/end`, `tool.call`,
  `tool.confirm`; arguments masked or hashed, files by hash, redacted upstream error bodies;
  rotation daily / hourly / by size, retention, owner-only permissions.
- Runtime: Streamable HTTP transport (manifest's MCP revisions only), Origin check, inbound auth
  `none` (loopback) and `static` (constant-time bearer keys, key name as audit actor),
  `/health/live` and `/health/ready`, optional management port. `oauth` inbound mode stops startup
  with a clear message until it is implemented.
- Runtime: discovery tools (`search_tools` / `describe_tool` / `call_tool`, and the catalog style),
  BM25 ranking, filtered by the instance's contract; packs audit (`audit_list`), introspection
  (`explain_tool`, `manifest_info`), schema (`describe_schema`) and diagnostics
  (`tls_certificate_fetch`, `connectivity_check`). Tools the runtime doesn't implement yet are not
  offered.
- Customer commands on the server: `doctor [--quick]`, `instance list`, `instance test <id>`,
  `instance trust <id> [--base64] [--yes]` (trust on first use, saved to `certs/<id>.pem`).
- `mcpgp explain <def> <tool>`: provenance of a tool's name, title and description.
- Customer commands that write config: `init` (guided), `instance add <id>` / `instance remove <id>`
  (in-place YAML edits that keep comments, `.bak`, rollback when the result doesn't load), and
  `client add claude-code|claude-desktop|cursor|vscode` (`--print`, `--dry-run`, backup, no secrets).
- Config reload while running: file watch (config, `.env`, certificate files), `SIGHUP`, and
  `<server> reload`; rejected configs keep the running one; the tool list follows instance changes
  (`list_changed`). Audit events `config.reload` and, for HTTP, `session.start` / `session.end`.
- A startup warning when `telemetry.enabled` is set (telemetry comes in M7).
- `mcpgp build` makes the package: the executable server jar with the manifest embedded, the
  manifest copy, the build's own `server.config.schema.json`, an example config and `SHA256SUMS`, in a
  reproducible zip. The CLI carries the runtime, so building needs neither Maven nor the network.
- Generated docs in the package: README, INSTALL, CONFIGURATION (every key with its environment
  variable), TOOLS, OPERATIONS; AGENTS.md and llms.txt with `build.docs: [agents]`.
- Package signing: Ed25519 over `SHA256SUMS` in minisign format (prehashed), `SIGNING-KEY.pub` in the
  package; `mcpgp keygen` and `mcpgp verify`.
- `platform` module (M5): the self-hosted platform. File store (content-addressed blobs, immutable
  versions, store format `0.1`, refused when different), servers and versions (zip or JSON uploads
  with path and size checks), files, validate and manifest preview with the CLI's compiler,
  archive/restore with ETags, identities and tokens with scopes and roles, `init` for the first
  administrator, activity log, and the API's own OpenAPI document.
- Naming: more verbs recognized (archive, restore, revoke, release, publish, assign, grant, …).
- Platform builds: queued and run in order with the CLI's compiler and packager, live log,
  artifacts (package zip, manifest, any single file of the package), signing with the platform's key
  (`signing.key`), build numbers for rebuilds, release (people only for now), archive / restore /
  delete (released builds archived first; records stay), daily retention.
- `packager` module: packaging moved out of the CLI, shared by the CLI and the platform.
- Platform library: shared, versioned items (contracts, naming, rules, packs, templates) that
  definitions reference as `mcpgp://<workspace>/<kind>/<name>@<version>`; servers are referenced the
  same way (variants). Duplicate and variant endpoints. Manifest previews follow new versions of
  what they reference.
- Platform accounts and guardrails: local accounts (PBKDF2-SHA256, lockout, sessions), approvals for
  agents' releases, agent quotas, export / import between releases.
- `schemas` module: JSON Schemas (draft 2020-12) for the definition, rule files, naming policy,
  name lock file, workspace mapping, runtime config, manifest, audit event, CLI config, tool tests and
  conformance files; a validator; tests that validate every example and conformance file.
- First conformance cases (`resolve/path-escapes-root`, `content-types/multipart-split`).
- CI: build and tests, link check.
- Initial specification: concepts, requirements, architecture, specs (definition format, naming,
  runtime config, tools contract, audit & errors, packaging), ADRs 0001–0013, protocol compatibility policy.
- Platform-side and client-side examples.
- Development process, roadmap, contribution guidelines, issue and PR templates.

- Web UI (M6, slice 1), served by the platform at `/`: React + Vite in `platform/web`, built into
  the jar. Sign-in with local accounts; All servers with duplicate / variant / new server; per
  server Overview (agent, customer setup, package and checks previews), Tools & rules with
  provenance, Connections, Sources (versions, files, uploads, checks), Builds (live log, downloads,
  release, archive, delete) and Activity; Shared library, All builds, API & agents (identities,
  tokens shown once, approvals). Bundled Geist fonts; nothing loaded from other hosts.
- Platform API: web UI sessions as an HttpOnly, SameSite=Strict cookie, with cookie-authenticated
  changes accepted only with the web client's header from the platform's own origin;
  `DELETE /sessions/current` (sign out), `GET /platform` (sign-in options, no token),
  `GET /settings`, `GET /servers/{id}/versions/{version}/example-config`, and `GET /activity?server=`;
  security headers (CSP, nosniff, no framing, no referrer) on every answer.

- Web UI: the server's settings and packs are edited on its Overview page, with a live preview of
  unsaved changes, a diff of the definition, and Save as the next version.
- Platform API: `POST /servers/{id}/edits` (set / remove at JSON Pointer paths with `[key=value]`
  list items; comments and formatting kept; dry run; refused when stale, not compilable, or not
  editable as text).

- Platform: company sign-in with OpenID Connect (authorization code with PKCE, through Spring
  Security) and LDAP / Active Directory (search and bind; encrypted connections only); groups map to
  roles, people in no mapped group get no identity, the first sign-in links the administrator `init`
  created (by verified email for OIDC). Identities record the account they sign in with.
- Web UI: the sign-in page offers the company account and says when directory accounts work.

- Multi-API servers end to end: the `ops-assistant` example compiles (orders, and fictional tickets
  and monitoring contracts), its manifest is pinned in `examples/manifest`, and a runtime test runs it
  against three target servers with basic, bearer and OAuth client-credentials sign-in.

- Platform MCP server: `platform/mcp` (definition plus the platform's OpenAPI document, moved there
  as the single source); `GET /api/v1/platform-mcp` builds and serves it as a package; the API & agents
  page offers it with its setup. Agents run it with their own token. Guide: docs/guides/agents.md.
- Naming: `edit` is a recognized verb (`editServer` → `server_edit`).

- Packaging: the `mcpb` target (a desktop MCP Bundle with the server jar; the install dialog asks for
  the target and its sign-in, or for the config file) and the `docker` target (`docker/Dockerfile`
  and `docker-compose.yml`: HTTP, non-root, `/config` and `/data`; the customer builds the image).
  `build.image.push` and `tar` are reported as not available yet.

- Runtime: inbound OAuth (`server.auth.mode: oauth`): the server is an OAuth resource server with
  protected resource metadata (RFC 9728), JWT access tokens checked against the issuer's JWKS with
  `aud` = this server (RFC 8707) through nimbus-jose-jwt, opaque tokens by introspection (RFC 7662),
  and scopes that decide which tools a client sees and calls (`403 insufficient_scope`).

- Config schema: `telemetry.prometheus` (`path`, `port`) removed; `/metrics` uses the server's ports.
- Runtime: telemetry, off by default (OpenTelemetry SDK): OTLP push (gRPC or HTTP) or Prometheus
  `/metrics` beside health (management port); a span per tool call with a child per upstream request; `mcpgp.tool.calls`,
  `mcpgp.tool.duration`, `mcpgp.instance.up`; never arguments, bodies or credentials. Generated
  OPERATIONS.md has a Telemetry section with an OpenTelemetry Collector example.

- Toolsets exposure: the compiler resolves `exposure.toolsets` (selectors, or one per tag) into the
  manifest; the runtime implements `toolsets_list`, `toolset_enable` and `toolset_disable` per session
  (stdio: the server's tool list; HTTP: `tools/list` per session and `list_changed` to that session
  only), and refuses tools of disabled toolsets with `TOOLSET_DISABLED`.

- Runtime: the recording pack (`recording_start`, `recording_stop`, `recording_list`,
  `recording_sessions`): a session's upstream calls, redacted before storage (secrets, redaction
  fields, credential headers), capped, owner-only, with retention.

- Resources: `expose: resource` on `GET` operations makes MCP resources and resource templates
  (`api://customers/{id}{?expand}`; the instance first in multi-instance builds). The compiler writes
  them to the manifest (`resources`) and refuses non-`GET` ones; the runtime serves
  `resources/list`, `resources/templates/list` and `resources/read` through the same path as tool
  calls, audited as `resource.read`.

- Prompts: the definition's prompt templates in the manifest (names through the naming pipeline,
  placeholders checked); the runtime serves `prompts/list`, `prompts/get` and `completion/complete`
  for enum arguments. Progress: `notifications/progress` every second during upstream calls when the
  client sent a `progressToken`.
- CLI: `mcpgp init [--from-spec <file|url>]` scaffolds a definition project (and a first
  `tests/smoke.yaml`); `mcpgp test` starts the packaged runtime in stdio mode against a mock made
  from the specs (response examples, else values from the schemas) and runs a smoke check and the
  test cases (exit code 4 on failures); `mcpgp push` uploads the files the definition uses as the
  server's next version on a platform (`MCPGP_TOKEN`), with `--build`, `--verify-local` (the
  platform's build must give agents the same as the local compile) and `--release`.
- Compiler model: media types keep their `example` (or first of `examples`).
- Platform: `GET /servers/{id}/versions/{version}/operations` lists every operation with what became
  of it (tool, resource, excluded by `select` and by which clause, hidden by a rule); edit dry runs
  take `operations: true`. Tools & rules → Operations: a switch per operation, with filters by text,
  state and tag, that writes `select` (one exclude entry per tool, or per tag; turning on what a
  broader clause leaves out asks first), previewed before saving as the next version.
- Platform: `GET /servers/{id}/versions/{version}/naming` (the policy reference, the server's own
  settings, the effective policy, the lock file's entries). Tools & rules → Naming: the policy in
  words, the vocabulary, and each tool's name with how it came about (policy, rule, lock), names the
  lock keeps although the policy would change them, and lock entries no tool uses.
- Package & deliver: `GET /servers/{id}/versions/{version}/package` (formats, files, generated docs
  and warnings of the next build, without building; `Packager.preview` shares the build's code) and a
  page that shows the formats, how the server runs, the docs as they ship (rendered, links between
  them open in place), the contents and the pipeline commands.
- Tools & rules → Naming: vocabulary entries (add, change, remove, override or switch off a shared
  policy's) and prefixes (`apis[].toolPrefix`, `naming.namespace.server`) are edited on the page,
  previewed with the tool names that change and those the lock file keeps, and saved as the next
  version. Naming policies: a `null` vocabulary entry switches off one of the extended policy.
- Shared naming policies are edited on the platform: `POST /library/naming/{name}/edits` (dry run
  with the servers that use the policy and the versions they pin) and an editor on the Shared library
  page for verbs from HTTP methods and vocabulary.
- Extensions (spec/extensions.md): the `mcpgp-extension-api` module (`CustomTool`,
  `RequestEnricher`, `CallFilter`, `ToolContext`, `ToolResult`, `OutgoingRequest`, `ApiClient`); the
  compiler checksums the declared jars, names custom tools and resolves filter matches into the
  manifest; the packager embeds the jars and lists them in OPERATIONS.md (CLI and platform builds);
  the runtime loads only the declared jars, checks their checksums and handler classes at startup,
  and runs filters, enrichers and custom tools where the spec says. Guide: docs/guides/extensions.md.
- Try a tool against a mock: `POST /servers/{id}/versions/{version}/try` runs the packaged server on
  the platform against a mock made from the spec (no real system is called; extensions left out) and
  answers the result and the requests it sent; Tools & rules → Try it. The mock and runner of
  `mcpgp test` moved to the packager (`ro.bitboy.mcpgp.packager.trial`), and the mock now resolves
  `$ref`s to shared schemas and records what it receives.
- Descriptions: `descriptions.notices` add text (company policy, disclaimers) to the descriptions of
  every tool or the tools a selector picks, of every kind, never cut off; rule selectors take `kind`
  and `tool`, so rules reach pack, built-in and custom tools; a too-long description loses spec text
  first, so what rules add stays whole; rules may have an `id`. Overview edits notices; Tools & rules
  edits a tool's title, description and parameter descriptions (an inline `ui:` rule). Platform: whole
  list items set or removed by `[key=value]` in edits, and `GET …/definition`.
- CLI: `mcpgp names accept <def> <tool…|--all>` writes `mcp-names.lock` (renames on purpose, and
  names the file doesn't have yet).

### Changed
- Naming: when a name comes from method and path (no operationId), PUT gives `replace` and PATCH
  `update` (PUT was `update` too, so PUT and PATCH on one path collided). Tools in `mcp-names.lock`
  keep their names; others named this way from a PUT change from `…_update` to `…_replace`.
  `methodVerbs` in a naming policy sets the verb per method (`GET`, `LIST`, `POST`, `PUT`, `PATCH`,
  `DELETE`), and Tools & rules → Naming edits it per server.
- OTLP telemetry moved from M3 (P0) to M7 (P1).

### Fixed
- **Try it** and `mcpgp test`: tools that a hybrid or discovery server doesn't list are called through
  `call_tool` (or `invoke_operation`), as an agent would, instead of failing with "Tool not found".
- Mock servers for **Try it** and `mcpgp test` run with a small fixed heap, and don't inherit
  `JAVA_TOOL_OPTIONS`.
- The page header's actions (the Tools / Operations / Naming switcher) keep their place.
- Discovery: in multi-API servers, `search_tools` matched every API's tools against the first API's
  instance of the given id (and its contract), so tools of other APIs sharing that id went missing.
- audit-event schema: `args` as `{ "sha256": … }` was rejected (`oneOf` matched two branches).
