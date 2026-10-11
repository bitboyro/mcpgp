# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com),
versions follow [SemVer](https://semver.org).

## Unreleased

### Added
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

### Changed
- OTLP telemetry moved from M3 (P0) to M7 (P1).

### Fixed
- Discovery: in multi-API servers, `search_tools` matched every API's tools against the first API's
  instance of the given id (and its contract), so tools of other APIs sharing that id went missing.
- audit-event schema: `args` as `{ "sha256": … }` was rejected (`oneOf` matched two branches).
