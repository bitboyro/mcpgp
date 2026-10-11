# How to run the platform on your own server

From an empty Linux host to a platform your team signs in to, with Docker Compose. What each part
is and why: [../deployment.md](../deployment.md). Every setting:
[../spec/platform-api.md §7](../spec/platform-api.md#7-platform-configuration-and-storage).

You'll need:
- a host with Docker Engine 24+ and the Compose plugin, 2 CPUs, 4 GB of memory, 20 GB of disk;
- a clone of this repository (the image is built from it);
- for a shared installation, a hostname and a TLS certificate at your reverse proxy;
- optionally, a client registration at your identity provider (step 7).

All commands run in `deploy/` unless a step says otherwise.

## 1. Create the secrets

```bash
cd deploy
chmod 700 secrets
openssl rand -base64 24 > secrets/admin_password     # the first administrator's password
openssl rand -base64 24 > secrets/db_password        # only for PostgreSQL
chmod 644 secrets/*_password
```

The containers run as their own users, so the files must be readable; the folder keeps other people
on the host out. Never commit them (the folder's `.gitignore` already refuses everything but its
README). To see the admin password when you first sign in: `cat secrets/admin_password`.

## 2. Settings

```bash
cp .env.example .env
```

Edit `.env`:

| Setting | Set it to |
|---|---|
| `MCPGP_PUBLIC_URL` | the address people will use: `https://mcpgp.example.com` (or `http://localhost:8443` to try it locally) |
| `MCPGP_WORKSPACE` | your organization's short name, as in `mcpgp://acme/naming/…` references (lowercase, digits, `-`) |
| `MCPGP_ADMIN` | the first administrator's name |
| `MCPGP_BIND` | `127.0.0.1` when the proxy runs on this host; `0.0.0.0` only behind a firewall |
| `COMPOSE_FILE` | which files to combine (next step), e.g. `compose.yaml:compose.postgres.yaml` |

`platform.yaml` holds the rest (sign-in, retention). Its defaults are fine to start.

## 3. Start it

Pick the store ([../deployment.md §4](../deployment.md#4-storage) compares them):

```bash
# file store, on the volume mcpgp_platform-data
docker compose up -d --build --wait

# or PostgreSQL (put COMPOSE_FILE=compose.yaml:compose.postgres.yaml in .env to make it the default)
docker compose -f compose.yaml -f compose.postgres.yaml up -d --build --wait
```

The first build of the image takes several minutes. `--wait` returns once the health check passes.
Then:

```bash
curl -s http://127.0.0.1:8443/healthz          # {"status":"up"}
docker compose logs platform | grep -E "administrator|deployment:"
```

The log says `created administrator <name>` once, and lists `deployment:` warnings for whatever
still needs doing (they go away as you follow the next steps).

## 4. Check it end to end

On a test installation (it creates a server named `smoke-orders-mcp`), from the repository root:

```bash
python3 deploy/smoke-test.py http://127.0.0.1:8443 admin deploy/secrets/admin_password
```

It checks health, signs in, adds the example naming policy and server, builds it and tries a tool
against the mock. Without a signing key (step 6) the build fails, because the example asks for
signed packages; that's expected until step 6.

## 5. Put it behind TLS

Point your proxy at `127.0.0.1:8443`, set `MCPGP_PUBLIC_URL=https://…` in `.env`, and run
`docker compose up -d` again. Caddy, which gets a certificate on its own:

```
mcpgp.example.com {
    reverse_proxy 127.0.0.1:8443
    request_body {
        max_size 50MB
    }
}
```

nginx:

```nginx
server {
    listen 443 ssl;
    server_name mcpgp.example.com;
    ssl_certificate     /etc/ssl/mcpgp.crt;
    ssl_certificate_key /etc/ssl/mcpgp.key;
    client_max_body_size 50m;
    proxy_read_timeout 120s;
    location / {
        proxy_pass http://127.0.0.1:8443;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Open `https://mcpgp.example.com` and sign in as the administrator with the password from
`secrets/admin_password`. To choose your own password, use the API (the web UI has no password page
yet), with a session token from signing in:

```bash
curl -X PUT https://mcpgp.example.com/api/v1/identities/<your id>/password \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"password":"<at least 12 characters>","currentPassword":"<the one from the file>"}'
```

## 6. Sign packages

```bash
mcpgp keygen --out keys            # keys/mcpgp.key (secret), keys/mcpgp.pub (publish it)
cp keys/mcpgp.key secrets/signing_key && chmod 644 secrets/signing_key
```

Add `compose.signing.yaml` to `COMPOSE_FILE` (or `-f compose.signing.yaml`) and run
`docker compose up -d`. Builds now sign their checksums; give customers `mcpgp.pub`. Keep
`mcpgp.key` in your secret store as well: it can't be recreated.

## 7. Company sign-in (optional)

OIDC (Entra ID, Okta, Keycloak, Google, …):

1. Register a web application at your identity provider. Redirect URI:
   `https://mcpgp.example.com/login/oauth2/code/oidc`. Scopes `openid profile email`, and a claim
   with the person's groups (often `groups`).
2. Put the client secret in `secrets/oidc_client_secret` (`chmod 644`), and add
   `compose.oidc.yaml` to `COMPOSE_FILE`. A public client (PKCE, no secret) needs neither.
3. Uncomment `auth.oidc` in `platform.yaml` and fill in `issuer`, `clientId`, `label` and `roles`:
   which groups get `viewer`, `builder`, `releaser` or `admin`. People in none of them can't sign in.
4. `docker compose up -d`, then sign in with the company button on the sign-in page.
5. Once it works, and someone in your admin group has signed in, set
   `auth: { local: { enabled: false } }` to turn off passwords.

LDAP or Active Directory works the same way with `auth.ldap` in `platform.yaml` (the keys are in
[../spec/platform-api.md §7](../spec/platform-api.md#7-platform-configuration-and-storage)); put the
service account's password in a secret file and reference it with `${file:/run/secrets/…}`.

## 8. Tokens for CI and agents

In **API & agents**, create an identity of kind `pipeline` (CI) or `agent`, give it a role, and
create a token for it; the token is shown once. Store it in your CI's secret store as
`MCPGP_TOKEN` and point the CLI at the platform: `mcpgp push --platform https://mcpgp.example.com`
([ci-pipeline.md](ci-pipeline.md), [agents.md](agents.md)).

## 9. Back up

Back up the store, `secrets/`, `.env` and `platform.yaml`.

```bash
# PostgreSQL, while it runs
docker compose exec -T db pg_dump -U mcpgp -Fc mcpgp > backup/mcpgp-$(date +%F).dump

# file store: stop, copy the volume, start
docker compose stop platform
docker run --rm -v mcpgp_platform-data:/data -v "$PWD/backup":/backup alpine \
    tar czf /backup/mcpgp-files-$(date +%F).tgz -C /data .
docker compose start platform
```

Restore into a fresh installation: start the database, `pg_restore -U mcpgp -d mcpgp --clean` the
dump (or untar into the empty volume), then start the platform. Test a restore once before you rely
on the backups.

## 10. Upgrade

```bash
git pull                                  # or set MCPGP_IMAGE to the new tag
docker compose up -d --build --wait
```

Read the [CHANGELOG](../../CHANGELOG.md) first and back up. If the platform logs that the store
"has format …", that release changed the store format: start the old release, export
(`GET /api/v1/export?artifacts=true` as an admin), start the new one on an empty store and import
the zip (`POST /api/v1/import`).

## 11. Move from the file store to PostgreSQL

```bash
docker compose stop platform
docker compose -f compose.yaml -f compose.postgres.yaml up -d --wait db
docker compose -f compose.yaml -f compose.postgres.yaml run --rm --no-deps platform \
    copy-store --from /var/lib/mcpgp --config /etc/mcpgp/platform.yaml
```

`copy-store` reads the file store from the volume and writes everything into the (empty) PostgreSQL
schema: servers, versions, the library, builds with their packages and logs, identities and their
passwords and tokens, approvals and the activity log. Then switch `COMPOSE_FILE` to include
`compose.postgres.yaml` and `docker compose up -d`. Keep the volume until you've checked the new
installation; nothing reads it any more.

## 12. When something's wrong

| You see | It means | Do |
|---|---|---|
| `admin.password (${file:/run/secrets/admin_password}) can't be read` | the secret file is missing, empty or unreadable for uid 10001 | create it, `chmod 644` it |
| `Another platform process has the store in schema mcpgp open` | a second platform runs on the same database (or a crashed one's session hasn't ended yet) | stop the other one; wait a minute for the database to drop the old session |
| `The store … has format 0.x` | an upgrade changed the store format | step 10 |
| `the definition asks for a signed package, but the platform has no signing.key` | a build needs signing | step 6 |
| sign-in works, then you're signed out at once | the cookie is `Secure` but you opened `http://` | open the `https://` address, or set `MCPGP_PUBLIC_URL` to `http://localhost:8443` for a local try |
| the company sign-in returns to `/sign-in?error=sso` | the redirect URI or issuer doesn't match | compare the redirect URI with `<publicUrl>/login/oauth2/code/oidc`; `docker compose logs platform` names the cause |
| `/sign-in?error=no-access` | the person is in no group mapped in `roles` | map their group, or check `groupsClaim` |
| `unhealthy` in `docker compose ps` | the platform can't reach its store | `docker compose logs platform db` |
