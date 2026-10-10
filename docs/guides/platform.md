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

Environment variables `MCPGP_PLATFORM_STORAGE__ROOT`, `MCPGP_PLATFORM_HTTP__HOST` and
`MCPGP_PLATFORM_HTTP__PORT` override the file. Back up by copying the storage root while the
platform is stopped. TLS: put the platform behind your reverse proxy for now.

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
