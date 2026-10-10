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
- `schemas` module: JSON Schemas (draft 2020-12) for the definition, rule files, naming policy,
  name lock file, workspace mapping, runtime config, manifest, audit event, CLI config, tool tests and
  conformance files; a validator; tests that validate every example and conformance file.
- First conformance cases (`resolve/path-escapes-root`, `content-types/multipart-split`).
- CI: build and tests, link check.
- Initial specification: concepts, requirements, architecture, specs (definition format, naming,
  runtime config, tools contract, audit & errors, packaging), ADRs 0001–0013, protocol compatibility policy.
- Platform-side and client-side examples.
- Development process, roadmap, contribution guidelines, issue and PR templates.

### Fixed
- audit-event schema: `args` as `{ "sha256": … }` was rejected (`oneOf` matched two branches).
