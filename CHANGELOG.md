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
- `schemas` module: JSON Schemas (draft 2020-12) for the definition, rule files, naming policy,
  name lock file, workspace mapping, runtime config, manifest, audit event, CLI config, tool tests and
  conformance files; a validator; tests that validate every example and conformance file.
- First conformance cases (`resolve/path-escapes-root`, `content-types/multipart-split`).
- CI: build and tests, link check.
- Initial specification: concepts, requirements, architecture, specs (definition format, naming,
  runtime config, tools contract, audit & errors, packaging), ADRs 0001–0013, protocol compatibility policy.
- Platform-side and client-side examples.
- Development process, roadmap, contribution guidelines, issue and PR templates.

### Changed
- OTLP telemetry moved from M3 (P0) to M7 (P1).

### Fixed
- audit-event schema: `args` as `{ "sha256": … }` was rejected (`oneOf` matched two branches).
