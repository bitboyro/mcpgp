# Spec: platform API

Status: **draft v1** · Owner: platform · Decisions: [ADR-0014](../decisions/0014-platform-self-hosted.md) (self-hosted),
[ADR-0011](../decisions/0011-platform-never-calls-targets.md) (never calls targets),
[ADR-0010](../decisions/0010-variants-extend.md) (variants)

The platform keeps definitions, builds packages from them, and records who did what. The web UI,
the CLI (`mcpgp push`) and agents all use this API; nothing the UI does is UI-only.

## 1. Principles

1. **Self-hosted, one organization** per installation (ADR-0014). No tenant in the model; shapes
   stay organization-neutral so a tenant can be added later.
2. **Same compiler and packager as the CLI.** A build on the platform and `mcpgp build` on the same
   definition version produce the same package.
3. **Immutable versions.** A definition version is a content-addressed snapshot of the project's
   files; it never changes. Edits create new versions.
4. **The platform never calls customer target servers** (ADR-0011) and fetches no specs over the
   network unless an administrator allows the host (§7).
5. **Everything is recorded:** every change, build, download, approval and sign-in in the activity log.

## 2. Resources

| Resource | What it is |
|---|---|
| **Server** | One MCP server definition project: id (slug), title, its versions, optional parent (variants) |
| **Version** | An immutable snapshot of the project's files (definition, contracts, overlays, rules, lock), identified by its digest |
| **Build** | Compiling and packaging one version: status, log, diagnostics, artifacts |
| **Library item** | A shared file set others reference as `mcpgp://<org>/<kind>/<name>@<version>` (kinds: `servers`, `contracts`, `naming`, `rules`, `packs`, `templates`) |
| **Identity** | A person, an agent or a pipeline, with scopes and tokens |
| **Approval** | A request (e.g. an agent's release) waiting for a person's decision |
| **Activity** | The append-only log of what happened |

### 2.1 Servers and versions

- `POST /servers` creates a server from a first version: a zip of the project, or JSON
  `{ files: { "<path>": "<content>" } }`. The root definition is the single `*.mcpgp.yaml` at the
  top. Files are checked with the same rules as the CLI (root restriction, no remote references).
- `POST /servers/{id}/versions` adds a version (same bodies), with an optional `message`. A version
  whose digest equals the current one is not created again.
- `GET /servers/{id}/versions/{version}/files[/<path>]` lists or returns files.
- `POST /servers/{id}/versions/{version}/validate` returns the compiler's diagnostics.
- `GET /servers/{id}/versions/{version}/manifest` returns the manifest (compiled on demand, cached by
  digest): what agents will get, with provenance. This is what the UI's previews show.
- `GET /servers/{id}/versions/{version}/operations` lists every operation of the version's contracts
  per API, with the API's `select` and, per operation, `state`: `tool` or `resource` (with its `tool`
  name), `excluded` (left out by `select`, with `exclusion: { kind, reason, index?, selector? }`:
  `kind` is `access`, `include`, `exclude` or `deprecated`; for `exclude`, the first matching entry and
  its index), or `hidden` (by a rule). The Tools & rules page turns operations on and off from it,
  writing `select` through the edits below. A version that doesn't compile gets `422`.
- `GET /servers/{id}/versions/{version}/example-config` returns the example configuration the
  version's package will ship (what the customer fills in), with secrets as references only.
- **Edits:** `POST /servers/{id}/edits { base, changes: [{ op, path, value }], message?, dryRun? }`
  changes the root definition of the latest version (`base`; another number gets `409`) and saves the
  result as the next version, like an upload. `op` is `set` (`null` is a value: in a variant it removes
  what is inherited) or `remove`; `path` is a JSON Pointer, plus `[key=value]` to pick a list item
  (`/apis/[id=orders]/select/access`; created when missing). The file is edited as text, so comments
  and formatting elsewhere stay; when that isn't possible, the edit is refused (`422`) rather than
  rewriting the file. An edit that doesn't compile is not saved (`422` with diagnostics). With
  `dryRun` nothing is saved: the answer is the edited definition, its manifest (or diagnostics) and
  example configuration, which is how the UI previews unsaved changes; `operations: true` adds the
  operation list (as `GET …/operations` gives it) for the edited definition.
- **Duplicate:** `POST /servers/{id}/duplicate { id, title }`: an independent copy of the current
  version's files. **Variant:** `POST /servers/{id}/variants { id, title, pin? }`: a new server whose
  definition `extends` this one (`@latest`, or `@<version>` with `pin`) and stores only its overrides.
- **Archive / restore** a server: it leaves the active list; its versions and builds stay.
- `version` in paths is a version number (`1`, `2`, …, per server) or `latest`.

### 2.2 Builds

- `POST /servers/{id}/builds { version, buildNumber?, versionOverride? }` queues a build; the answer
  is `202` with the build. Builds run one at a time per server, in order.
- Status: `queued` → `running` → `succeeded` | `failed`. `GET /builds/{id}` shows the status,
  diagnostics and log; `GET /builds/{id}/log` streams the log while it runs.
- Artifacts (packaging.md §1): `GET /builds/{id}/artifacts` lists them;
  `GET /builds/{id}/artifacts/{name}` downloads the package zip, the manifest or any single file of the
  package. Downloads are recorded in the activity log.
- **Release:** `POST /builds/{id}/release` marks a succeeded build as released (the version customers
  get). Builds started by agents need an approval first (§5).
- **Archive / restore / delete** (packaging.md §2): archived builds leave the active list and stay
  downloadable; delete removes the files but keeps the record and the activity; **released builds must
  be archived before they can be deleted**.
- **Retention** (installation settings): auto-archive after N days, delete archived files after M
  days, always keep the last K builds per server; released builds are never deleted automatically.
- **Signing:** packages are signed with the builder's key from the platform config (a secret
  reference, never stored in the store).

### 2.3 Library

`/library/{kind}/{name}` with versions, like servers. The workspace name in references is the
platform setting `workspace` (default `local`); references to another workspace don't resolve. An item
with several files names its `root` (the file references point to). Manifest previews are recomputed
when a referenced item or server gets a new version. Definitions reference items as
`mcpgp://<org>/<kind>/<name>@<version|latest>`; the platform resolves them from its library when it
compiles (the CLI does the same with `--workspace`).

## 3. Identities, sign-in and scopes

| Kind | Signs in with | Typical scopes |
|---|---|---|
| **person** | company SSO (OIDC), the company directory (LDAP / Active Directory), or a local account; then a session (UI) or personal tokens (API) | everything their role allows |
| **agent** | API token | `servers:read`, `servers:write`, `builds:run` |
| **pipeline** | API token | `servers:write`, `builds:run`, `builds:release` |

- **Tokens:** bearer tokens (RFC 6750), shown once, stored as hashes, with an expiry and scopes;
  revocable. Every request names its identity in the activity log.
- **Local accounts:** passwords stored as PBKDF2-HMAC-SHA256 hashes (salted, 600 000 iterations),
  minimum length 12; repeated failures slow down and lock the account temporarily. Can be turned off
  when SSO is configured.
- **OIDC:** authorization code flow with PKCE against the configured issuer (`/oauth2/authorization/oidc`,
  callback `/login/oauth2/code/oidc`); the provider's settings come from its discovery document, read
  at the first sign-in. The ID token's `groupsClaim` (default `groups`) maps to roles. A successful
  sign-in becomes the same session cookie as a local one.
- **LDAP / Active Directory:** names without a local account are checked against the directory through
  `POST /sessions`: the service account (`bindDn`, its password a reference) finds the person with
  `userSearch`, then the platform binds as them, so the directory checks the password and the
  platform stores none; groups come from `groupSearch` (their `cn`). With `activeDirectory.domain`,
  the bind is `name@domain` and groups come from `memberOf`. Connections are `ldaps://` or `ldap://`
  with `startTls: true` (plain `ldap://` only to the platform's own host). Repeated failures lock the
  name for 15 minutes, as for local accounts; an empty password is refused (it would be an anonymous bind).
- **Groups and roles:** `roles` maps group names (ignoring case) to roles; at least one is required.
  People in no mapped group get no identity and the same answer as a wrong password (the reason is in
  the activity log). Identities created this way (`owner: directory`) follow their groups at every
  sign-in and are refused once no group maps; identities made on the platform keep the scopes an
  administrator gave them.
- **Linking:** people are matched by their account (OIDC `issuer` + `sub`, the LDAP DN). At the first
  sign-in, a person on the platform without a company account is linked when their name is the
  account's **verified** email (OIDC: user names may be editable at the provider) or its user name
  (LDAP). Identities show `signsInWith: oidc | ldap`.
- **Bootstrap:** `platform init` creates the first administrator and prints a one-time admin token;
  name them as they sign in (their email for OIDC, their user name for LDAP) so the first company
  sign-in links to them. With `MCPGP_PLATFORM_ADMIN_PASSWORD` set, the administrator also gets a local
  password.
- **Sign-in:** `POST /api/v1/sessions { name, password }` → a session token (12 hours); an unknown name
  and a wrong password get the same answer. `GET /api/v1/platform` (no token) says which ways of
  signing in the installation offers. `DELETE /api/v1/sessions/current` signs out (revokes the session
  token; API tokens are revoked with `DELETE /tokens/{id}` instead).
- **Web UI sessions:** the web UI sends `X-Mcpgp-Client: web` on every request. Its sign-in answer
  carries the session token only as a cookie (`mcpgp_session`; `HttpOnly`, `SameSite=Strict`, `Path=/`,
  `Secure` over HTTPS), never in the body, so the page's scripts can't read it. A request authenticated
  by that cookie that changes something (anything but `GET`/`HEAD`/`OPTIONS`) must carry the header,
  and its `Origin`, when present, must name the host it was sent to; otherwise `403`. Other sites can
  send neither without a CORS preflight, which the platform never grants. Requests with an
  `Authorization` header are unaffected. `PUT /identities/{id}/password` sets a password (admins:
  anyone's; people: their own, with the current one). `auth.local.enabled: false` turns local accounts
  off.

Scopes:

| Scope | Allows |
|---|---|
| `servers:read` | read servers, versions, files, manifests, builds, artifacts |
| `servers:write` | create servers and versions, duplicate, variant, archive |
| `builds:run` | start builds |
| `builds:release` | release builds (agents: only through an approval) |
| `builds:delete` | archive, restore and delete builds |
| `library:write` | create and version library items |
| `approvals:decide` | approve or reject requests |
| `admin` | identities, tokens, settings, retention |

**Platform MCP server:** `GET /platform-mcp` returns the platform's own MCP server as a package, built
from the project in `platform/mcp` (this API's OpenAPI document and a definition) with the same
compiler and packager as any build, and signed with the platform's key when there is one. Agents run it
themselves, configured with the platform's address and **their own token** as a bearer credential; the
platform never forwards tokens. Sign-in, passwords and export / import are left out of its tools.

Roles bundle scopes: **viewer** (`servers:read`), **builder** (viewer + `servers:write`, `builds:run`,
`library:write`), **releaser** (builder + `builds:release`, `builds:delete`, `approvals:decide`),
**admin** (all).

## 4. API conventions

- Base path `/api/v1`; JSON; `Authorization: Bearer <token>`.
- Errors: `application/problem+json` (RFC 9457) with a stable `code` (`NOT_FOUND`, `FORBIDDEN`,
  `CONFLICT`, `VALIDATION`, `APPROVAL_REQUIRED`, …).
- Lists: `{ items, nextCursor }`, `?cursor=` and `?limit=` (default 50, max 200).
- Optimistic concurrency on server metadata: `ETag` / `If-Match`; a stale write gets `412`.
- The API is described by its own OpenAPI document (`/api/v1/openapi.json`), so the platform's MCP
  server (M7) is generated by mcpgp from it.

## 5. Agent guardrails and approvals

- An agent's **release** creates an approval instead of releasing (`202` with the approval); a person
  with `approvals:decide` approves (the build is released) or rejects it, with a comment. Agents never
  decide approvals, whatever their scopes.
- Agents may not delete builds, change identities or settings, or allow spec hosts.
- Quotas per agent: builds per hour (default 20), versions per hour (default 60; new servers count
  as versions); over the limit → `429 QUOTA_EXCEEDED`. Settings: `guardrails: { agentBuildsPerHour,
  agentVersionsPerHour }`.
- Destructive API calls (delete, archive) by agents require the agent's token to have the scope and
  are recorded with the agent and its owner.

## 6. Activity

Append-only: `ts`, `actor` (identity, kind, via token or session), `action` (`server.create`,
`version.create`, `build.start`, `build.finish`, `build.release`, `artifact.download`, `approval.decide`,
`identity.create`, `token.create`, `signin`, `signout`, …), `target`, `outcome`, details. `GET /activity`
with filters: `target`, `server` (the server's own entries and those of its builds), `actor`, `action`
(or its prefix), `limit`. Never contains secrets or token values.

## 7. Platform configuration and storage

`platform.yaml` (or environment variables `MCPGP_PLATFORM_…`, as for servers):

```yaml
workspace: acme                    # the <workspace> of mcpgp:// references (default local)
http: { host: 0.0.0.0, port: 8443, publicUrl: https://mcpgp.acme.internal }   # publicUrl: for the OIDC callback behind a proxy
storage:
  type: files                      # files (now) | postgres (later)
  root: /var/lib/mcpgp
auth:
  local: { enabled: true }
  oidc:                            # company SSO
    issuer: https://login.acme.com
    clientId: mcpgp
    clientSecret: "${env:MCPGP_OIDC_SECRET}"   # none: a public client (PKCE only)
    scopes: [openid, profile, email] # the default
    groupsClaim: groups              # the default
    label: Acme SSO                  # on the sign-in button
    roles: { mcpgp-admins: admin, mcpgp-builders: builder }
  ldap:                            # the company directory
    url: ldaps://ldap.acme.internal/dc=acme,dc=com
    bindDn: cn=mcpgp,ou=services,dc=acme,dc=com
    bindPassword: "${env:MCPGP_LDAP_PASSWORD}"
    userSearch: { base: "ou=people", filter: "(uid={0})" }        # {0}: the name typed in
    groupSearch: { base: "ou=groups", filter: "(member={0})" }    # {0}: the person's DN, {1}: the name
    # activeDirectory: { domain: acme.com }                      # instead of the searches
    roles: { mcpgp-admins: admin, mcpgp-releasers: releaser }
signing: { key: "${env:MCPGP_SIGNING_KEY}" }
specHosts: []                      # hosts definitions may fetch specs from (none by default)
retention: { archiveAfterDays: 90, deleteArchivedAfterDays: 365, keepLast: 10 }
```

### 7.1 Storage

All data goes through one store interface; two implementations are planned:

| Store | For | Status |
|---|---|---|
| **files** | single-node installations: a directory on disk; backup is a copy of it | M5 |
| **postgres** | larger installations, several platform nodes; files in the database or object storage | later |

File store layout:

```
<root>/
├─ store.json                  { "format": "0.x" } — the store format version
├─ servers/<id>/server.json    metadata (title, parent, archived, version list)
├─ blobs/sha256/<ab>/<digest>  file contents, content-addressed (shared by all versions)
├─ versions/<digest>.json      a version: path → blob digest
├─ builds/<id>/                build.json, log.txt, artifacts/
├─ library/<kind>/<name>/      like servers
├─ identities.json             identities, token hashes, password hashes
└─ activity/activity-YYYY-MM.jsonl
```

**Before 1.0.0 there are no store migrations.** The store records its format version; a platform
release with another pre-1.0 format refuses to open it, naming both versions, and data moves with the
export / import API (`GET /export`, `POST /import`: a zip of servers, versions, library and builds'
records). From 1.0.0 the format is migrated forward automatically on start, with a backup first.

## 8. Security considerations

- Uploaded projects are checked like CLI projects: root restriction, no remote references (unless
  the host is in `specHosts`), size limits per file and per project.
- Builds run the compiler and packager only; nothing from a definition is executed (extensions are
  compiled code the builder adds to the platform, never uploaded with a definition).
- Tokens and passwords are stored as hashes; the signing key, the OIDC client secret and the LDAP
  service account's password are references. Directory passwords are never stored.
- `GET /settings` gives builders the workspace name, sign-in options, whether a signing key is
  configured (never the key), retention and guardrails.
- The web UI (M6) is served by the platform itself, from its own origin: every answer carries a
  Content-Security-Policy allowing only the platform's own scripts, styles, fonts and API
  (`default-src 'self'`, no inline scripts or styles, no framing), `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer` and `X-Frame-Options: DENY`. Fonts are bundled; the UI loads nothing
  from other hosts. After sign-in it only returns to paths on the platform.
- The activity log is append-only through the API; it records identities, never secrets.

See [../security.md](../security.md).
