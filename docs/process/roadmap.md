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
| **M5 · Platform API** ✓ | Builds as a service | [spec/platform-api.md](../spec/platform-api.md): file store, servers and versions, validate/preview, builds (download/archive/delete/release, retention, signing), duplicate/variant, library, identities (tokens, local accounts) with scopes, approvals, activity; REST API with its own OpenAPI. PostgreSQL store later |
| **M6 · Web UI** ✓ | Final-direction UI | Pages from the design canvas on top of the API, editing a server's settings on its page, sign-in with local accounts, OIDC and LDAP / Active Directory. Follow-ups in [design/ui.md](../design/ui.md) |
| **M7 · P1 wave** ✓ | Breadth | OTLP telemetry (moved from M3, 2026-10-10; ✓ with Prometheus /metrics), OAuth inbound (✓), OAuth CC + mTLS downstream (✓), discovery/hybrid/toolsets (✓), desktop bundle (✓), Docker (✓ files; image push/tar later), multi-API servers (✓ end to end), agent docs (✓), platform MCP server (✓, a package agents run with their own token), recording pack (✓) |
| **M8 · P1 completion** (in progress) | The rest of P1 | Resources (✓), prompts with completion and progress (✓); CLI `push`, `test`, `init --from-spec` (✓); UI follow-ups (operations on and off on Tools & rules (✓), Naming section (✓), try against the mock, formats on Package & deliver (✓)); codegen mode and the extension SPI (✓) |

Deferred: harness-lab comparison; per-user downstream identity; other languages.

Last, and only after they are thought through (2026-10-11): the issue-tracker, skills and agent-feedback
packs (whether they make sense at all), and paging normalization (APIs already page their own way;
the one real gap is paging links in response headers).

## Open questions (M0)

1. Skills pack: storage format and history (git, files, database)?
2. "Issue area" in the existing internal MCP server: local store or Jira mapping?
3. ~~Hosting of the platform itself: self-hosted only, or also a hosted offering?~~ Self-hosted,
   single-tenant for now ([ADR-0014](../decisions/0014-platform-self-hosted.md)).