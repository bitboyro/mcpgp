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

- `POST /servers/from-spec` creates a server from an API's spec: an OpenAPI 3.0 / 3.1 or Swagger 2.0
  document in JSON or YAML, as one file or several that `$ref` each other (JSON `{ files }` or a zip;
  other files are left out). The platform finds the main file (the one with `openapi:` or `swagger:`
  that no other file references; `main=<path>` picks it when several could be), follows every `$ref`
  from it, and builds the project as `mcpgp init --from-spec` does: `<id>.mcpgp.yaml`, the spec under
  `contracts/` (folders kept) and `tests/smoke.yaml`. `id`, `title` and `version` default to the
  spec's (`info.title` → `orders-api-mcp`, `info.version`). Files picked without their folders are put
  where the spec refers to them when exactly one uploaded file has that name (the answer lists them as
  `moved`). Problems are answered before anything is created, as `422 SPEC_PROBLEMS` with `missing`
  (`{ file, from, pointer }` for each referenced file that isn't in the upload), `remote` (references
  to URLs, which the platform never fetches), `candidates` and `problems` (e.g. OpenAPI 3.2).
  `dryRun=true` answers `{ id, title, api, version, main, format, files, moved, tools, exists }`.
  Operations that would get the same tool name (often the same `operationId` twice) are answered as
  `422 NAME_COLLISIONS` with `collisions: [{ operation, method, path, operationId, name, suggested,
  base }]`: each later operation, the name it clashes on, and the name it would get from its method
  and path (`base` is that name without the API prefix). The user chooses, and the choice is saved in
  the definition: `renames=<JSON {operation key: name}>` writes a rule per operation (matched by
  method and path, `name: { set }`), or `collisions=method-path` sets `naming.collisions`.
- `GET /servers/{id}/diff?from=&to=` (`servers:read`) says what changed between two versions, as
  `mcpgp diff` does (cli.md §3.5): `from` and `to` are version numbers, `latest` or `released` (the
  version of the server's last released build); by default from the last release (else the version
  before the latest) to the latest. The answer: `from`, `to` (`{ version, serverVersion }`),
  `released`, `summary`, `breaking`, `changes: [{ impact: breaking | notable | info, tool, kind,
  text }]` and the same as `markdown`. The web UI: the server's Changes page, and "What changed since
  the last release" next to Release on a build.
- `POST /servers/{id}/contracts` adds a contract version from an uploaded spec (same bodies and
  checks): to the server's API (`api=<id>` when it has several), as `version` (default the spec's
  `info.version`), with `default=true` to make it the default; the same version again replaces its
  spec. The files go under `contracts/<version>/`, and the server gets its next version. `dryRun=true`
  answers `{ api, version, spec, replaces, compiles, diagnostics, moved, unchanged }`, and when it
  compiles, what agents would notice: `summary`, `breaking`, `changes` (as `/diff`).
  - Instead of a body, `url=<address>` **fetches the spec** from where it is published: one file
    (named after the address's last segment, else `openapi.json` / `openapi.yaml`), from a host in
    `specHosts` only (§7; `422 SPEC_HOST_NOT_ALLOWED` otherwise, also for a redirect to another
    host), HTTPS unless the host is listed with `http://`, at most 10 MB, 30 s. The address is recorded
    as the contract's `source`; a failed fetch is `502 SPEC_FETCH_FAILED`. The dry run adds `source`
    and `fetched: { url, file, bytes }`.
  - `source=true&version=<v>` fetches the address recorded for that version again (**check for
    updates**).
  - A spec the same as the saved one (same files, same address) answers `unchanged: true`; saving it
    answers `200` and makes no version. A spec uploaded by hand over a fetched one removes its
    `source`.
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
- `GET /servers/{id}/versions/{version}/naming` returns the version's naming: `extends` (the shared
  policy's reference, or null), `own` (what the definition sets in `naming` itself), `effective` (the
  merged policy the compiler used) and `lock: { present, entries: [{ id, name, since }] }`. Each tool's
  name steps are in the manifest's provenance; the Naming section of Tools & rules shows both.
- `POST /servers/{id}/versions/{version}/try/warm` (`builds:run`) starts the version's trial server in
  the background (`202`), so the first `try` doesn't wait for it; the web UI calls it when the Try panel opens.
- `POST /servers/{id}/versions/{version}/try { tool, arguments }` (`builds:run`) calls a tool of the
  version as an agent would, against a **mock** of its API: the server mcpgp packages runs on the
  platform with the version's manifest, its instance (`mock`) is a loopback server that answers every
  operation with the spec's examples (or values made from the response schemas), and nothing reaches a
  real system. The answer: `result` (or a JSON-RPC `error`), `requests` (what the server sent to the
  mock: method, path, query, headers without credentials, body, status) and `leftOut`. Extensions are
  left out (custom tools, enrichers, call filters): the platform never runs builders' code; `mcpgp
  test` runs it. A few servers stay running per version and stop after five idle minutes.
- `GET /servers/{id}/versions/{version}/definition` returns the definition as data: `root` (the root
  file as written), `resolved` (with what it extends, includes and its naming policy merged in) and
  `noticeSources` (notice id → the shared set it comes from). The pages read notices and their own
  rules from it.
- `GET /servers/{id}/versions/{version}/package` says what a build of the version will contain,
  without building it: `zip` (the name, before the build number), `formats` (`jar` always, `mcpb` and
  `docker` per `build.targets`, each with its main file), `files` (everything in the zip, as a build
  writes it), `docs` (each generated document in full), `warnings`, and `signed` (whether the platform
  signs its builds). Package & deliver shows it.
- `GET /servers/{id}/versions/{version}/example-config` returns the example configuration the
  version's package will ship (what the customer fills in), with secrets as references only.
- **Edits:** `POST /servers/{id}/edits { base, changes: [{ op, path, value }], message?, dryRun? }`
  changes the root definition of the latest version (`base`; another number gets `409`) and saves the
  result as the next version, like an upload. `op` is `set` (`null` is a value: in a variant it removes
  what is inherited) or `remove`; `path` is a JSON Pointer, plus `[key=value]` to pick a list item
  (`/apis/[id=orders]/select/access`; created when missing). A path ending in `[key=value]` sets the
  whole item (replaced, or added at the end) or, with `remove`, removes it
  (`/descriptions/notices/[id=policy]`, `/overrides/rules/[id=ui:listAccounts]`). The file is edited as text, so comments
  and formatting elsewhere stay; when that isn't possible, the edit is refused (`422`) rather than
  rewriting the file. An edit that doesn't compile is not saved (`422` with diagnostics). With
  `dryRun` nothing is saved: the answer is the edited definition, its manifest (or diagnostics) and
  example configuration, which is how the UI previews unsaved changes; `operations: true` adds the
  operation list (as `GET …/operations` gives it) for the edited definition.
- **The platform's own server:** `mcpgp-platform` (the platform's MCP server, §3) is one of the
  servers, listed first, with `system: true`. The platform makes it at its first start from the project
  bundled in its release, and adds a version (author `system`) at a start whose bundled project differs
  from its latest version: an upgrade adds one, a restart none. It is read like any server (manifest,
  operations, try, package) and can be built, duplicated and made a variant of; edits, uploads,
  contracts and archiving are `409 SYSTEM_SERVER`. A variant holds a company's changes (hide tools,
  add notices) and follows its new versions. `apply` of shared notices without `servers` leaves it
  out. A server someone else already called `mcpgp-platform` is left alone (a warning at start).
  `platformServer: { enabled: false }` in `platform.yaml` archives it at the next start.
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

Naming policies are edited on the platform too: `GET /library/naming/{name}` adds `policy` (the
latest version as data), `root` and `usedBy: [{ server, pinned }]` (servers whose latest version
extends the policy; `pinned` is the version they name, null for none or `latest`).
`POST /library/naming/{name}/edits { base, changes, message?, dryRun? }` edits the policy file like
server edits (comments and layout kept; `base` must be the latest version, else `409`); a dry run
answers `{ text, problems, usedBy }`, a save makes the next version (`422` when the result isn't a
valid naming policy). A new version reaches servers that don't pin one; pinned servers keep theirs
until their definition moves. Other kinds are versioned by upload.

**Shared notices** (kind `notices`, definition-format.md §5.1) work the same way: a new version is
checked against the notices schema (`422` when it doesn't match); `GET /library/notices/{name}` adds
`set` (the latest version as data), `root` and `usedBy` (servers whose latest version includes it in
`descriptions.include`); `POST /library/notices/{name}/edits` edits it (notices as whole items,
`/notices/[id=…]`). And they can be put on many servers at once:

`POST /library/notices/{name}/apply { servers?, version?, message? }` (scope `servers:write`) adds
`mcpgp://<workspace>/notices/<name>@<version>` to each server's `descriptions.include`, or moves a
server that includes the set at another version to this one. Without `servers`: every server that
isn't archived; without `version`: the latest. Each changed server gets its next version (an edit
like any other; one that wouldn't compile is left alone). The answer has one line per server:
`{ server, status: updated | unchanged | inherited | failed, version?, from?, error? }`; a variant
of a chosen server is `inherited` (it gets the notices from its parent). The activity log records
`library.apply`.

## 3. Identities, sign-in and scopes

| Kind | Signs in with | Typical scopes |
|---|---|---|
| **person** | company SSO (OIDC), the company directory (LDAP / Active Directory), or a local account; then a session (UI) or personal tokens (API) | everything their role allows |
| **agent** | API token | `servers:read`, `servers:write`, `builds:run` |
| **pipeline** | API token | `servers:write`, `builds:run`, `builds:release` |

- **Tokens:** bearer tokens (RFC 6750), shown once, stored as hashes, with an expiry and scopes;
  revocable. Every request names its identity in the activity log. `GET /tokens` says when each was
  `lastUsed` (to the minute; recorded apart from the token, so it never undoes a revocation); the web
  UI's Connect an agent waits for it.
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
  `Authorization` header are unaffected. `PUT /identities/{id}/password { password, currentPassword?,
  mustChange? }` sets a password (admins: anyone's, with `mustChange: true` as a temporary one;
  people: their own, with the current one). A person with a temporary password gets
  `mustChangePassword: true` from sign-in and `GET /me`, and every other request answers
  `403 PASSWORD_CHANGE_REQUIRED` until they set their own (not the temporary one again); `GET /me`,
  `GET /settings` and signing out still work. Five wrong passwords lock an account for 15 minutes;
  `POST /identities/{id}/unlock` (admins) ends it, for local and directory sign-ins. `GET /identities`
  shows people's `password` (whether they have a local one), `mustChangePassword` and `lockedUntil`.
  The web UI: Change password on the Account page (the name in the sidebar), Set password and Unlock
  on a person in API & agents. `auth.local.enabled: false` turns local accounts off.

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
The same project is the platform's own server (§2.1), and **Connect an agent** in the web UI (on that
server, and on API & agents) creates an agent with its first token and shows what to paste into each
client (Claude Code, Claude Desktop, Cursor, VS Code); people who aren't admins are asked to have an
admin create the agent.

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
http:
  host: 0.0.0.0
  port: 8443
  publicUrl: https://mcpgp.acme.internal   # what people type; the OIDC callback; Secure cookies when https://
  tls:                                     # leave out: plain HTTP for a proxy in front (the default)
    certificate: /etc/mcpgp/tls/certificate.pem   # PEM, the full chain (a path)
    privateKey: /run/secrets/tls_key              # PKCS#8 PEM (a path to the file, not a reference)
    # or: keystore: /run/secrets/tls_keystore (PKCS12), password: "${file:/run/secrets/tls_keystore_password}"
    # renewed files are picked up without a restart
storage:
  type: files                      # files (the default) | postgres
  root: /var/lib/mcpgp             # files: the directory
  # type: postgres
  # url: jdbc:postgresql://db:5432/mcpgp
  # user: mcpgp
  # password: "${file:/run/secrets/db_password}"
  # schema: mcpgp                  # the default; created when missing
  # poolSize: 10                   # the default
admin:                             # the first administrator (a local account), created when the store has no identities
  name: ana
  password: "${file:/run/secrets/admin_password}"
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
specHosts: []                      # hosts specs may be fetched from (none by default; §2.1, §8):
#  - api.acme.com                     #   HTTPS on 443
#  - specs.acme.com:8443              #   another port
#  - http://build.acme.internal       #   plain HTTP, for an internal host
#  - { host: specs.acme.com, header: Authorization, value: "${env:SPECS_TOKEN}" }  # a credential, as a reference
retention: { archiveAfterDays: 90, deleteArchivedAfterDays: 365, keepLast: 10 }
platformServer: { enabled: true }  # the platform's own MCP server among the servers (§2.1); false archives it
```

Environment variables override the file: `MCPGP_PLATFORM_WORKSPACE`, `MCPGP_PLATFORM_HTTP__HOST`,
`…HTTP__PORT`, `…HTTP__PUBLIC_URL`, `…STORAGE__TYPE`, `…STORAGE__ROOT`, `…STORAGE__URL`,
`…STORAGE__USER`, `…STORAGE__PASSWORD`, `…STORAGE__SCHEMA`, `…STORAGE__POOL_SIZE`, `…SIGNING__KEY`,
`…ADMIN__NAME`, `…ADMIN__PASSWORD`, `…HTTP__TLS__CERTIFICATE`, `…HTTP__TLS__PRIVATE_KEY`,
`…HTTP__TLS__KEYSTORE`, `…HTTP__TLS__PASSWORD`, `…PLATFORM_SERVER__ENABLED` (all with the prefix `MCPGP_PLATFORM_`). Secrets (`password`,
`clientSecret`, `bindPassword`, `signing.key`, `http.tls.password`) are references, in the file and
in the environment alike. A web sign-in from a plain `http://` page to a platform whose `publicUrl`
is `https://` is refused with `409 WRONG_ADDRESS` naming the address to open (the browser would drop
its Secure cookie); `GET /platform` includes `publicUrl` for the sign-in page. `http.publicUrl` is `https://` (or `http://` for a local test); when it is `https://`, session
cookies are always `Secure`. At start the platform logs `deployment:` warnings for settings unfit for
a shared installation ([../deployment.md §10](../deployment.md#10-warnings-at-start)).

Commands of the platform jar (and image): `setup [--out <dir>] [options]` (writes the Docker
Compose files, `platform.yaml`, `.env` and generated secrets for this image into a folder; never
overwrites; `--upgrade` rewrites the compose files and `MCPGP_IMAGE`), `check` (what would fail at
start with this config, changing nothing; exit code 1 when something would), `init --admin <name>`
(the first administrator, once, printing a one-time admin token), `copy-store --from <directory>`
(copies a file store into the configured, empty store, with build packages, build logs and the
activity log), and no command: run. The config file is `--config <file>`, else
`MCPGP_PLATFORM_CONFIG` (the image sets `/etc/mcpgp/platform.yaml`), else `platform.yaml` in the
working directory.

### 7.1 Storage

All data goes through one store interface with two implementations; both pass the same tests:

| Store | For | Status |
|---|---|---|
| **files** | single-node installations: a directory on disk; backup is a copy of it | M5 |
| **postgres** | shared installations: records as JSON in one schema, file contents as `bytea`; backup with `pg_dump` | ✓ ([ADR-0015](../decisions/0015-postgres-store.md)) |

Either store serves one platform process: the file store per directory, PostgreSQL per schema (an
advisory lock held while the store is open; a second process is refused at start).

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

PostgreSQL tables (schema `mcpgp`): `store_info` (the format), `servers`, `library`, `versions`,
`blobs`, `builds`, `build_files`, `build_logs`, `records` (identities, tokens, token uses, credentials,
approvals), `activity`, `cache`. Records are `json` (not `jsonb`, which would reorder their keys).

**Before 1.0.0 there are no store migrations.** The store records its format version; a platform
release with another pre-1.0 format refuses to open it, naming both versions, and data moves with the
export / import API (`GET /export`, `POST /import`: a zip of servers, versions, library and builds'
records). From 1.0.0 the format is migrated forward automatically on start, with a backup first.

### 7.2 Health

`GET /healthz` (public, outside `/api/v1`): `200 {"status":"up"}` when the store can be reached,
`503 {"status":"down"}` when it can't. Nothing else; for container health checks, load balancers
and monitoring.

## 8. Security considerations

- Uploaded projects are checked like CLI projects: root restriction, no remote references (unless
  the host is in `specHosts`), size limits per file and per project.
- The platform fetches specs only from hosts an administrator lists in `specHosts` (none by
  default), only when someone asks (adding a version, checking for updates), never on a schedule,
  and never from a customer's target server. Redirects are followed only to another listed host. A
  host's credential is a reference, read at each fetch and sent only to that host; it is never
  stored with a server, shown, or written to the activity log. Agents can fetch from listed hosts
  (with `servers:write`) but can't change `specHosts`, which is in the platform's config file.
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
