# Self-hosted deployment

What a platform installation is made of, and how its parts fit: the container, the store, the
network, sign-in, secrets, backups and upgrades. The step-by-step setup is in
[guides/self-hosting.md](guides/self-hosting.md); every configuration key is in
[spec/platform-api.md §7](spec/platform-api.md#7-platform-configuration-and-storage).

The platform is self-hosted and single-tenant ([ADR-0014](decisions/0014-platform-self-hosted.md)):
one installation per organization, run on your own infrastructure.

## 1. What runs

```
                 people (browser)        CI, agents (tokens)
                         │                       │
                         ▼                       ▼
                ┌──────────────────────────────────────┐
                │ your reverse proxy: TLS, the hostname│   https://mcpgp.example.com
                └───────────────────┬──────────────────┘
                                    │ http, port 8443
                ┌───────────────────▼──────────────────┐
                │ platform container (mcpgp-platform)  │──► identity provider (OIDC) or
                │ REST API · web UI · builds · try     │    directory (LDAP), when configured
                └───────────────────┬──────────────────┘
                     file store     │     or     PostgreSQL store
             ┌──────────────────────▼─┐     ┌─────────────────────────┐
             │ volume /var/lib/mcpgp  │     │ db container (or your    │
             │                        │     │ PostgreSQL), schema mcpgp│
             └────────────────────────┘     └─────────────────────────┘
```

| Part | What it is |
|---|---|
| **platform** | One Java process (the image `mcpgp-platform`, built from [`deploy/Dockerfile`](../deploy/Dockerfile)). It serves the web UI and the REST API on one port, compiles and packages servers (one build at a time, in the process), and runs servers against a mock for **Try it** (up to 3 at a time, as child processes on the container's loopback, stopped after 5 idle minutes). |
| **store** | Where everything lives: servers, versions and their files, the library, builds and their packages, identities, tokens, password hashes, approvals, the activity log. Either a directory (the **file store**) or a **PostgreSQL** database. One platform process per store. |
| **reverse proxy** | Yours (nginx, Caddy, Traefik, an ingress, a load balancer). It terminates TLS and forwards to the platform. The platform doesn't do TLS itself. |
| **identity provider / directory** | Optional. People sign in with a company account through OIDC, or against LDAP / Active Directory. Local accounts (name and password kept by the platform) work without either. |

**What the platform contacts.** Only its store, and your identity provider or directory when you
configure one. It never calls the APIs the generated servers talk to
([ADR-0011](decisions/0011-platform-never-calls-targets.md)): builds read the specs you upload, and
**Try it** answers from a mock built from the spec's examples. Builds don't download anything, and
the packages they make never contact the platform.

## 2. The files in `deploy/`

| File | Purpose |
|---|---|
| `Dockerfile` | The image `mcpgp-platform` (published on Docker Hub for `amd64` and `arm64`, or built here). It builds from the repository: the web UI, the platform jar and the runtime it packages servers with. Runs as uid 10001, listens on 8443, has a health check, and keeps its data in the volume `/var/lib/mcpgp`. |
| `compose.yaml` | The platform with the file store on a named volume. |
| `compose.postgres.yaml` | Adds PostgreSQL 17 (data on its own volume, no published port) and switches the store to it. |
| `compose.signing.yaml` | Signs built packages with `secrets/signing_key`. |
| `compose.oidc.yaml` | Mounts the OIDC client secret for company sign-in. |
| `platform.yaml` | The platform's config, mounted read-only at `/etc/mcpgp/platform.yaml`: workspace, public URL, sign-in, retention. |
| `.env.example` | Compose settings (public URL, workspace, admin name, published port). Holds no secrets. |
| `secrets/` | One file per secret, mounted at `/run/secrets/<name>`. Only its README is committed. |
| `smoke-test.py` | Checks an installation end to end: health, sign-in, a build, a try. Creates test data. |

The compose files combine: `-f compose.yaml -f compose.postgres.yaml -f compose.signing.yaml`, or
the same list in `COMPOSE_FILE` in `.env` (separated by `:`).

## 3. Configuration

Three layers, later ones winning:

1. `platform.yaml` (the image has a default that only sets the file store).
2. Environment variables `MCPGP_PLATFORM_<SECTION>__<KEY>`, for the settings a deployment changes
   most (the list is in [spec/platform-api.md §7](spec/platform-api.md#7-platform-configuration-and-storage)).
   Sign-in with OIDC or LDAP is configured in `platform.yaml` only.
3. Secrets: every secret in the config is a reference, `${file:/run/secrets/db_password}` or
   `${env:NAME}`, never the value. The platform refuses a plain value where a secret belongs. In
   compose files, write `$${file:…}` (compose would read `${…}` itself).

## 4. Storage

| | File store | PostgreSQL store |
|---|---|---|
| Set with | `storage: { type: files, root: /var/lib/mcpgp }` (the default) | `storage: { type: postgres, url: jdbc:postgresql://db:5432/mcpgp, user, password, schema }` |
| Holds | a directory: JSON records, content-addressed files, build packages, monthly activity logs | the same records as JSON in one schema (default `mcpgp`); files, packages and logs as `bytea` |
| Good for | one machine, a trial, a team that backs up volumes | a shared installation, backups and monitoring with your database tooling |
| Backup | stop the platform, copy the directory or volume | `pg_dump` while it runs |
| Several platform processes | no: one process per directory | no: one process per schema; a second one is refused at start (an advisory lock) |

Both stores behave the same way: the same test suite runs on both, and the store is the only thing
that changes. The file store stays the default; nothing needs PostgreSQL.

The PostgreSQL store creates its tables on first start in the configured schema and records the
store format there. The database account needs to create a schema and tables the first time;
afterwards it only reads and writes.

`copy-store` moves a file store's data into an empty store (the usual case: into PostgreSQL), with
build packages, build logs and the activity log:

```bash
java -jar mcpgp-platform.jar copy-store --from /var/lib/mcpgp --config platform.yaml
```

**Sizing.** Each build keeps its package (a few tens of MB with the runtime inside). Retention
archives and then deletes the packages of old builds (`retention`; released builds and the last 10
per server are kept), so storage grows with releases, not with every build. Give the platform
container 2 GB of memory: the platform's heap is half the container's limit, and each warm **Try it**
server (up to 3) runs with a 256 MB heap next to it.

## 5. Network, TLS and the public URL

- The platform listens on plain HTTP, port 8443 (`http.port`). Publish it only to the proxy:
  `MCPGP_BIND=127.0.0.1` when the proxy runs on the same host.
- Set `http.publicUrl` to the address people use (`https://mcpgp.example.com`). It is the base of
  the OIDC redirect URI, and when it is `https://` session cookies are always `Secure`. The proxy
  should also send `X-Forwarded-Proto: https`.
- The proxy has to accept uploads up to 50 MB (a project's limit) and keep requests open for
  about a minute (a build's package download, the first **Try it** of a version).
- `GET /healthz` answers `200 {"status":"up"}` when the platform can reach its store and `503`
  when it can't. It is public and says nothing else; use it for the container health check, the
  proxy and your monitoring.

## 6. Who can sign in

| Who | How |
|---|---|
| The first administrator | `admin.name` and `admin.password` (in compose: `MCPGP_ADMIN` and `secrets/admin_password`). Created on the first start, when the store has no identities; later starts leave it alone, so changing the secret afterwards changes nothing. Or once by hand: `init --admin <name>`. |
| People | Local accounts (an administrator creates the person in **API & agents** and sets the password through the API), and/or company accounts through OIDC or LDAP. Directory groups map to the roles `viewer`, `builder`, `releaser`, `admin` in `roles`; someone in no mapped group can't sign in. |
| CI and agents | API tokens of their own identity (kind `pipeline` or `agent`), with only the scopes they need; created in **API & agents**. Agents get approvals and quotas ([spec/platform-api.md §5](spec/platform-api.md#5-agent-guardrails-and-approvals)). |

Once company sign-in works, turn local accounts off (`auth.local.enabled: false`). Keep one admin
reachable: give someone in your admin group the `admin` role through `roles` before turning them off.

## 7. Signing

With `signing.key` set (a minisign secret key from `mcpgp keygen`), every build signs its
`SHA256SUMS`; customers check packages with the public key `mcpgp.pub`, which you publish. A
definition can require signed packages, and the platform then refuses to build it without a key.
Back the key up with the other secrets: a lost key means a new key, and every customer has to trust
the new public key.

## 8. Backups and restore

Back up three things: **the store**, **the secrets** (`deploy/secrets/`, or wherever yours live) and
**your `platform.yaml` and `.env`**.

| Store | Back up | Restore |
|---|---|---|
| files | stop the platform; copy the volume (`docker run --rm -v mcpgp_platform-data:/data -v "$PWD":/backup alpine tar czf /backup/mcpgp-files.tgz -C /data .`) | into an empty volume, then start |
| postgres | `docker compose exec db pg_dump -U mcpgp -Fc mcpgp > mcpgp.dump` (consistent while it runs) | `pg_restore -U mcpgp -d mcpgp --clean mcpgp.dump`, then start the platform |

The export (`GET /api/v1/export?artifacts=true`, admin) is a store-independent copy of the data
(without build logs and the activity log). It is how data moves between platform releases before 1.0.

## 9. Upgrades

- Use a tagged image (`MCPGP_IMAGE`) and move to the next tag after reading the
  [CHANGELOG](../CHANGELOG.md). Back up first.
- **Before 1.0.0 the store format isn't migrated.** When a release changes it, the new platform
  refuses to open the old store and says so; move the data with the old release's export and the
  new release's import (into an empty store). From 1.0.0 the store is migrated forward on start.
- A restart takes the platform away for the time it starts (about 10 seconds). Builds that were
  running are marked failed and can be started again; nothing else is lost.

## 10. Warnings at start

The platform logs `deployment: …` warnings for settings that are fine on a laptop but not on a
shared installation: no `http.publicUrl` while listening on all interfaces; an `http://` public URL
that isn't localhost; no `signing.key`; a file store under `/tmp`; no administrator yet; local
accounts still on next to company sign-in. They don't stop the platform. Read them after each change.

## 11. Checklist for a shared installation

- [ ] TLS at the proxy; `http.publicUrl` is `https://…`; the platform's port isn't reachable directly.
- [ ] PostgreSQL store (or a file store on a backed-up volume), with backups tested by a restore.
- [ ] Secrets in files outside the repository; the secrets folder readable only by the deploying user.
- [ ] `signing.key` set, and `mcpgp.pub` published where customers find it.
- [ ] Company sign-in with roles mapped; local accounts off, or only the break-glass admin.
- [ ] Tokens for CI and agents with the fewest scopes; expiry dates set.
- [ ] `/healthz` monitored; the container's logs collected.
- [ ] No `deployment:` warnings in the log.
