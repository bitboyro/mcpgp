# Running the platform

The platform is self-hosted ([ADR-0014](../decisions/0014-platform-self-hosted.md)): one jar, one
data directory. What it does: [spec/platform-api.md](../spec/platform-api.md).

```bash
# once: the first administrator and a one-time admin token
java -jar mcpgp-platform.jar init --admin ana --config platform.yaml

# then
java -jar mcpgp-platform.jar --config platform.yaml
```

`platform.yaml`:

```yaml
http: { host: 127.0.0.1, port: 8443 }
storage: { type: files, root: /var/lib/mcpgp }
```

Environment variables `MCPGP_PLATFORM_…` override the file (the list:
[spec/platform-api.md §7](../spec/platform-api.md#7-platform-configuration-and-storage)). Back up by
copying the storage root while the platform is stopped. TLS: put the platform behind your reverse
proxy. For a shared installation (a container image, Docker Compose, PostgreSQL, company sign-in,
backups) follow [self-hosting.md](self-hosting.md).

## The web UI

Open `http://<host>:<port>/` (for example `http://127.0.0.1:8443/`) and sign in with a local account.
Give the administrator a password once, either with `MCPGP_PLATFORM_ADMIN_PASSWORD` set when you run
`init`, or with the admin token:

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"password":"<at least 12 characters>"}' localhost:8443/api/v1/identities/<id>/password
```

**Start from your API's spec:** All servers → **New server** → drop your OpenAPI or Swagger file
(JSON or YAML; Swagger 2.0, OpenAPI 3.0 or 3.1). When it refers to other files (`$ref`), drop them
too, or choose the spec's folder or a zip of it; the page says which files are still missing before
anything is created, and puts files picked without their folders where the spec expects them. It
shows what it found (title, version, how many operations become tools), you adjust the server id and
title, and **Create server** makes the server, ready to shape on its Overview. A newer spec goes on
the server's **Sources** page: **Add a spec version** (or **Replace** a version's spec), optionally as
the default; each makes the server's next version. Teams that keep the definition in git upload the
whole project instead (**A definition project**, or `mcpgp push`).

The pages: **All servers** (new server from a spec or a project, duplicate, variant), and per server **Overview**
(what agents get, what the customer configures, the package, the checks), **Tools & rules** (each
tool as the agent gets it, and why), **Connections**, **Sources** (versions, files, upload a new
version), **Package & deliver** (the formats the next build makes, how it runs, its docs as they ship,
the package's files and the pipeline commands, all before building), **Builds** (download, release, archive, delete) and **Activity**; plus the **Shared library**,
**All builds**, **Activity** and **API & agents** (identities, tokens, approvals). The UI uses the same
API as everyone else; nothing it does is UI-only. On Overview, the server's settings (what agents
see, what they may do, target servers, transports, confirmation, packs) can be changed on the page:
the preview shows the result before anything is saved, Review shows the lines of the definition that
change, and Save makes the next version. On Overview, **Notices on tool descriptions** adds company policy or disclaimers to every tool, a
tag's tools, the tools that change data, or chosen tools; notices are never cut off. Text that every
server should carry lives once in the **Shared library** under **Shared notices**: create a set there
(**New shared notices**), add and change its notices (saved as the set's next version), and **Use
version N on … servers** puts it on every server (or the ones you tick), or moves them to the new
version; each changed server gets its next version. On a server's Overview the card lists the shared
sets it uses (with **Use @N** when a newer version exists, and **Remove**), offers the others, and
marks each shared notice with its set. On Tools & rules,
**Try it** on a tool calls it against a mock of the API (made from the spec's examples; nothing
reaches a real system) and shows what the agent gets back and the HTTP request the server sent;
extensions are left out there (`mcpgp test` runs them). **Edit title and descriptions** on a tool changes its title, description and parameter descriptions
(saved as an inline rule of the definition, shown under "Why it looks like this"; **Back to the spec**
removes it). On Tools & rules, **Operations** lists every operation of the
contracts with a switch: on makes it a tool (or resource), off leaves it out, and each operation that
is off says why (`excluded by tag system`, `read and write: no deletes`, `hidden by a rule`). Turning a
tool off adds one `select.exclude` entry for that operation (or one `{ tag: [...] }` entry for a whole
tag); turning an operation on undoes what left it out, and when that reaches further (removing a broad
exclude entry, changing `access`), the page says so and asks first. Unsaved switches are previewed by
the platform, and saving makes the next version, as on Overview. Operations a rule hides change in the
rule's file. **Naming** shows the naming policy in words (and which settings the server adds to a
shared policy), the vocabulary, and every tool's name with how it came about: from the policy, set by
a rule, or kept by `mcp-names.lock`, including names the lock keeps although the policy would now
give another one (accept those with `mcpgp names accept`). Vocabulary and prefixes can be changed
there: add or change an entry, remove the server's own, override or switch off one of a shared
policy (written as `null` in the server's `naming`), and set each API's `toolPrefix` and the server
prefix (`naming.namespace.server`). Before saving, the page shows which tool names change and which
the lock file keeps; renaming a tool breaks clients that call it. A prefix or suffix for chosen tools
only is a rule (`name: { prepend | append }`) in the definition's files. Shared naming policies
are edited on the **Shared library** page (open a policy): verbs from HTTP methods and vocabulary,
previewed as a diff of the policy file and saved as its next version. The page lists the servers that
use the policy; those that pin an older version keep it until their definition moves. Everything else is changed in the files (upload a new version on Sources).

**Company sign-in.** Add `auth.oidc` (SSO) or `auth.ldap` (the directory) to `platform.yaml`; the
sign-in page then offers them (the keys: [spec/platform-api.md §7](../spec/platform-api.md#7-platform-configuration-and-storage)).
Register the platform at your identity provider with the redirect URI
`<publicUrl>/login/oauth2/code/oidc`, and set `http.publicUrl` when the platform is behind a proxy.
Map your groups to roles under `roles`: people in no mapped group can't sign in. Run `init --admin`
with the administrator's email (OIDC) or directory user name (LDAP), so their first company sign-in
becomes that administrator; `auth.local.enabled: false` then turns local accounts off.

The session is an HttpOnly cookie valid 12 hours. Behind a reverse proxy that terminates TLS, pass
`X-Forwarded-Proto: https` so the cookie is marked `Secure`, and keep the `Host` header (the platform
compares a browser's `Origin` with it).

Working on the UI itself: `cd platform/web && npm run dev` serves it on `127.0.0.1:5173` with `/api`
proxied to a platform on port 8443 (`MCPGP_API_PORT` picks another). `mvn -pl platform package`
builds it into the jar; `-Dskip.installnodenpm -Dskip.npm` leaves it out.

## First steps with the API

```bash
TOKEN=mcpgp_…                                     # from init
curl -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/me

# a server from a project folder (zipped)
(cd orders && zip -qr - .) | curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/zip" \
     --data-binary @- "localhost:8443/api/v1/servers?id=orders&title=Orders&message=first"

curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/servers/orders/versions/latest/validate
curl -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/servers/orders/versions/latest/manifest

# an agent identity with the builder role, and its token (shown once)
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"kind":"agent","name":"build-bot","role":"builder"}' localhost:8443/api/v1/identities
```

Builds:

```bash
# build the latest version, then download the signed package
curl -X POST -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/servers/orders/builds
curl -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/builds/<id>            # status
curl -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/builds/<id>/log
curl -OJ -H "Authorization: Bearer $TOKEN" localhost:8443/api/v1/builds/<id>/artifacts/orders-mcp-2.0.0.zip
```

Packages are signed when `signing.key` is set (a reference, e.g. `"${env:MCPGP_SIGNING_KEY}"`; create the
key with `mcpgp keygen`). Retention: `retention: { archiveAfterDays: 90, deleteArchivedAfterDays: 365, keepLast: 10 }`.

Library and variants (`workspace: acme` in `platform.yaml` resolves `mcpgp://acme/…`):

```bash
# a shared naming policy, referenced as mcpgp://acme/naming/acme-standard@1
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d "{\"files\":{\"acme-standard.yaml\":$(jq -Rs . < acme-standard.yaml)}}" \
     "localhost:8443/api/v1/library/naming?name=acme-standard"

# a variant that extends orders-mcp, and an independent copy
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"id":"orders-readonly"}' \
     localhost:8443/api/v1/servers/orders-mcp/variants
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"id":"orders-copy"}' \
     localhost:8443/api/v1/servers/orders-mcp/duplicate
```

The API describes itself at `/api/v1/openapi.json`.
