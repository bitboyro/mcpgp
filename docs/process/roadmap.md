# Roadmap

Milestones, in order. Each ends with something demonstrable. Scope per item: see
[../requirements.md](../requirements.md) (P0 unless noted).

| Milestone | Goal | Exit criteria |
|---|---|---|
| **M0 · Specification** (now) | Specs, ADRs, examples, process | This repo's docs reviewed; open questions below answered |
| **M1 · Schemas & conformance** | Make the specs machine-checkable | JSON Schemas for definition, runtime config, manifest, audit event; examples validate in CI; first conformance fixtures with expected manifests |
| **M2 · Compiler core (Java)** | Definition → manifest | Resolver (multi-file, root restriction, workspace refs), OpenAPI 2.0/3.0/3.1 parsing, selection, naming pipeline + lock, rules, schema shaping, validation; golden tests pass for all examples |
| **M3 · Java runtime + CLI** | Run a server from a manifest | stdio + HTTP, single/multi instance, basic/apiKey/bearer, audit file, OTLP, audit + introspection packs; `mcpgp build/validate/explain`; operator `init/doctor/instance` |
| **M4 · Packaging** | Deliverable package | Spring Boot jar, generated config schema, human docs, checksums; CI check that the package makes no calls outside the customer's systems |
| **M5 · Platform API** | Builds as a service | Definition store, builds (download/archive/delete), duplicate/variant, identities; REST API |
| **M6 · Web UI** | Final-direction UI | Pages from the design canvas on top of the API |
| **M7 · P1 wave** | Breadth | OAuth inbound, OAuth CC + mTLS downstream, discovery/hybrid, desktop bundle, Docker, multi-API servers, agent docs, platform MCP server, more packs |

Deferred: harness-lab comparison; per-user downstream identity; resources/prompts; other languages.

## Open questions (M0)

1. Skills pack: storage format and history (git, files, database)?
2. "Issue area" in the existing internal MCP server: local store or Jira mapping?
3. Hosting of the platform itself: self-hosted only, or also a hosted offering?