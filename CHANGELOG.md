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
- `schemas` module: JSON Schemas (draft 2020-12) for the definition, rule files, naming policy,
  name lock file, workspace mapping, runtime config, manifest, audit event, CLI config, tool tests and
  conformance files; a validator; tests that validate every example and conformance file.
- First conformance cases (`resolve/path-escapes-root`, `content-types/multipart-split`).
- CI: build and tests, link check.
- Initial specification: concepts, requirements, architecture, specs (definition format, naming,
  runtime config, tools contract, audit & errors, packaging), ADRs 0001–0013, protocol compatibility policy.
- Platform-side and client-side examples.
- Development process, roadmap, contribution guidelines, issue and PR templates.
