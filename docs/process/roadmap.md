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
| **M8 · P1 completion** (in progress) | The rest of P1 | Resources (✓), prompts with completion and progress (✓); CLI `push`, `test`, `init --from-spec` (✓); UI follow-ups (operations on and off on Tools & rules (✓), Naming section (✓), try against the mock (✓), formats on Package & deliver (✓), the platform's own server first with Connect an agent (✓)); codegen mode and the extension SPI (✓) |
| **M9 · Self-hosting** (in progress) | Run it at work | Platform image and Docker Compose (✓), PostgreSQL store with `copy-store` (✓), deployment settings: first admin from config, `/healthz`, secure cookies behind TLS, start-up warnings (✓), company sign-in wired into compose (✓, OIDC and LDAP existed), [deployment.md](../deployment.md) and the [how-to](../guides/self-hosting.md) (✓); publishing the image to Docker Hub (✓, `publish` workflow); next: passwords in the web UI (0.3.0) |

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
| **0.3.0** (planned) | Passwords in the web UI: **Change password** in the account menu (current password, the new one twice); **Set password** on a person in API & agents, for admins (a temporary password, optionally to be changed at the next sign-in); **Unlock** for accounts locked after wrong passwords. Company accounts show none of this. |
| TODO, not yet scheduled | Marked TODO in the docs: `release.versionFrom` other than `contract`, `platform:` in the repository config; git and URL sources on the platform; a native CLI binary. (CLI `diff`, `preview`, `try`, `migrate` and `--no-color`, the platform's Changes page, and contract detection for `auto` instances: on `main`.) |

## Next (agreed 2026-10-13, not scheduled into a release)

Releases happen when they're asked for; these land on `main` first.

| Item | What | State |
|---|---|---|
| Tool-name clashes on spec upload | When two operations would get the same tool name, the upload shows them with suggested names (from method and path) to accept or edit, or sets a rule for the server (`naming.collisions`); both are saved in the definition | on `main` |
| 0.3.0 passwords | See the release table | on `main` |
| UX and wording review | See Main priorities | planned |
| Per-user identity by token exchange | `tokenExchange`: RFC 8693 or Entra on-behalf-of, for a shared server whose users sign in with OAuth (auth.md §2.5) | on `main` |
| Store performance at scale | Activity searches filtered by the store (SQL and indexes on PostgreSQL) and bounded in time; the cache cleanup batched | on `main` |

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