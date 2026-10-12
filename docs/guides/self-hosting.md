# How to run the platform on your own server

From an empty Linux host to a platform your team signs in to, with Docker Compose. What each part
is and why: [../deployment.md](../deployment.md). Every setting:
[../spec/platform-api.md §7](../spec/platform-api.md#7-platform-configuration-and-storage).

You'll need:
- a host with Docker Engine 24+ and the Compose plugin, 2 CPUs, 4 GB of memory, 20 GB of disk;
- for a shared installation, a hostname and a TLS certificate at your reverse proxy;
- optionally, a client registration at your identity provider (step 7).

No clone of the repository: the image is on Docker Hub, and the image writes its own setup. (To build
the image yourself instead, see [Building from the repository](#building-from-the-repository).)

## 1. Write the folder with `setup`

```bash
mkdir mcpgp && cd mcpgp
docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:0.2.5 setup
```

It asks for the public address (`https://mcpgp.example.com`, or `http://localhost:8443` to try it
locally), the workspace name (your organization's short name, as in `mcpgp://acme/…` references),
the store (PostgreSQL or files; [../deployment.md §4](../deployment.md#4-storage) compares them),
whether to sign packages, for an `https://` address who serves HTTPS (step 5), and optionally
company sign-in. Then it writes:

```
mcpgp/
├─ compose.yaml               the platform, pointing at this image (no build section)
├─ compose.postgres.yaml      PostgreSQL, when you chose it
├─ compose.signing.yaml       package signing, when you chose it
├─ compose.oidc.yaml          the OIDC client secret, for company sign-in with a secret
├─ compose.tls.yaml           HTTPS by the platform (or compose.tls-files.yaml, with your certificate)
├─ tls/certificate.pem        the platform's certificate (self-signed: import it where it should be trusted)
├─ platform.yaml              sign-in, retention (and auth.oidc, when you gave an issuer)
├─ .env                       MCPGP_IMAGE, COMPOSE_FILE (the files above), public address, bind address
├─ .gitignore                 keeps .env and secrets/ out of git
├─ signing_key.pub            the public key customers check packages with: publish it
└─ secrets/                   700; the files 644, generated, never printed
   ├─ admin_password
   ├─ db_password
   └─ signing_key
```

Without questions, for scripts:
`setup --yes --public-url https://mcpgp.example.com --workspace acme --store postgres --sign`
(`setup --help` lists every option). It never overwrites a file: run it again and it keeps what is
there. The `--user` flag makes the files yours rather than the container's.

The containers read the secrets as their own users, so the files are readable and the folder keeps
other people on the host out. Back up `secrets/` and `.env` somewhere safe (a password manager, your
secret store): the signing key can't be recreated. To see the admin password for the first sign-in:
`cat secrets/admin_password`.

## 2. Check, then start

```bash
docker compose run --rm platform check      # what would go wrong: secrets, the database, the identity provider
docker compose up -d --wait
```

`check` changes nothing; it prints `✓ ready to start`, or what to fix. `--wait` returns once the
health check passes. Then:

```bash
curl -s http://127.0.0.1:8443/healthz          # {"status":"up"}
docker compose logs platform | grep -E "administrator|deployment:"
```

The log says `created administrator <name>` once, and lists `deployment:` warnings for whatever
still needs doing (they go away as you follow the next steps).

## 3. Settings you may change later

`.env` holds what the compose files use:

| Setting | Set it to |
|---|---|
| `MCPGP_IMAGE` | the image and version to run |
| `COMPOSE_FILE` | which files to combine, e.g. `compose.yaml:compose.postgres.yaml:compose.signing.yaml` |
| `MCPGP_PUBLIC_URL` | the address people use: `https://mcpgp.example.com` |
| `MCPGP_WORKSPACE` | your organization's short name, as in `mcpgp://acme/naming/…` references |
| `MCPGP_ADMIN` | the first administrator's name (used on the first start only) |
| `MCPGP_BIND` | `127.0.0.1` when the proxy runs on this host; `0.0.0.0` only behind a firewall |

`platform.yaml` holds the rest (sign-in, retention). After a change: `docker compose up -d`.

## 4. Check it end to end (optional)

On a test installation (it creates a server named `smoke-orders-mcp`), from a clone of the repository
(the test uses its examples):

```bash
python3 deploy/smoke-test.py http://127.0.0.1:8443 admin ~/mcpgp/secrets/admin_password
```

It checks health, signs in, adds the example naming policy, shared notices and server, builds it,
tries a tool against the mock, and checks that the shared notices reach the tools. Without a signing
key the build fails, because the example asks for signed packages.

## 5. HTTPS

For an `https://` address, `setup` asks who does HTTPS:

| Answer | What you get |
|---|---|
| **1. here, self-signed** (the default when asked) | The platform serves HTTPS itself on the port in your address (e.g. 9443), with a certificate made for your host, valid a year. Browsers warn until they trust it: import `tls/certificate.pem` (your browser's or OS's certificate settings), or replace it with yours (answer 2). |
| **2. here, with my files** | The platform serves HTTPS with your certificate (the full chain, PEM) and its private key (PKCS#8 PEM, `BEGIN PRIVATE KEY`). Put both files in the folder first. A renewed certificate is picked up without a restart: copy it over `tls/certificate.pem` and the key over `secrets/tls_key`. |
| **3. my own proxy** (the default with `--yes`) | The platform stays plain HTTP on `127.0.0.1:8443` for your proxy, ingress or load balancer (below). |

Switch any time in the folder; it changes only the HTTPS pieces and `COMPOSE_FILE`, `MCPGP_BIND`,
`MCPGP_PORT` in `.env`:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<version> setup --tls self-signed
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<version> setup --tls files --cert certificate.pem --key key.pem
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<version> setup --tls proxy
docker compose run --rm platform check && docker compose up -d --force-recreate --wait
```

`check` says whether the certificate and key belong together, are for the host in your address, and
how long they last (a warning 30 days before they expire). The public address must always be what
people type, scheme, host and port: `https://mcpgp.example.com:9443`.

**With your own proxy:** point it at `127.0.0.1:8443`, keep `MCPGP_PUBLIC_URL=https://…` in `.env`,
and run `docker compose up -d` again. Caddy, which gets a certificate on its own:

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

Your first server: **New server** on All servers, then drop your API's OpenAPI or Swagger file (JSON
or YAML), and the files it refers to if it has any.

## 6. Sign packages

When you answered yes in `setup`, packages are signed already: give customers `signing_key.pub`
(`mcpgp verify <package> --key signing_key.pub`). To turn it on later: run
`setup --sign` again in the folder (it adds `compose.signing.yaml` and the key, and keeps everything
else), add `compose.signing.yaml` to `COMPOSE_FILE` in `.env`, and `docker compose up -d`. A key of
your own (`mcpgp keygen`) works too: put it in `secrets/signing_key` (`chmod 644`).

## 7. Company sign-in (optional)

OIDC (Entra ID, Okta, Keycloak, Google, …):

1. Register a web application at your identity provider. Redirect URI:
   `https://mcpgp.example.com/login/oauth2/code/oidc`. Scopes `openid profile email`, and a claim
   with the person's groups (often `groups`).
2. Put the client secret in `secrets/oidc_client_secret` (`chmod 644`). When you gave `setup` an
   issuer it already added `compose.oidc.yaml` to `COMPOSE_FILE`; otherwise add it. A public client
   (PKCE, no secret) needs neither.
3. In `platform.yaml`, `auth.oidc` (written by `setup`, or uncomment it) has `issuer`, `clientId`,
   `label` and `roles`: set which of your groups get `viewer`, `builder`, `releaser` or `admin` (the
   groups `setup` writes are examples). People in none of them can't sign in. `docker compose run --rm
   platform check` tells you whether the issuer answers.
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
# the new image updates the compose files and MCPGP_IMAGE; secrets/, platform.yaml and the rest of .env stay
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<new version> setup --upgrade
docker compose run --rm platform check
docker compose pull && docker compose up -d --wait
```

Read the [CHANGELOG](../../CHANGELOG.md) first and back up. If the platform logs that the store
"has format …", that release changed the store format: start the old release, export
(`GET /api/v1/export?artifacts=true` as an admin), start the new one on an empty store and import
the zip (`POST /api/v1/import`).

## 11. Move from the file store to PostgreSQL

If `setup` wrote a file store, first run `setup --store postgres` again in the folder: it adds
`compose.postgres.yaml` and `secrets/db_password` and keeps the rest.

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
| `This platform's address is https://…; open it there` when signing in | you opened a plain `http://` address of a platform whose address is `https://`, where the browser can't keep the sign-in | open the address it names; or, for a quick try without HTTPS, set `MCPGP_PUBLIC_URL` to the `http://` address you use and `docker compose up -d --force-recreate platform` |
| you can't connect from another machine | the platform listens on `127.0.0.1` only (`MCPGP_BIND`), and nothing serves the port in your address yet | let the platform serve HTTPS (`setup --tls self-signed`), or put your proxy on that port; check a network firewall or cloud security group too |
| the company sign-in returns to `/sign-in?error=sso` | the redirect URI or issuer doesn't match | compare the redirect URI with `<publicUrl>/login/oauth2/code/oidc`; `docker compose logs platform` names the cause |
| `/sign-in?error=no-access` | the person is in no group mapped in `roles` | map their group, or check `groupsClaim` |
| `unhealthy` in `docker compose ps` | the platform can't reach its store | `docker compose logs platform db` |

## Building from the repository

Instead of the published image, build it on the host from a clone; the files are the ones in
`deploy/` (with a `build:` section), and the secrets are made by hand:

```bash
git clone https://github.com/bitboyro/mcpgp && cd mcpgp/deploy
chmod 700 secrets
openssl rand -base64 24 > secrets/admin_password     # the first administrator's password
openssl rand -base64 24 > secrets/db_password        # only for PostgreSQL
chmod 644 secrets/*_password
cp .env.example .env                                 # then set the values of step 3
docker compose -f compose.yaml -f compose.postgres.yaml up -d --build --wait
```

The first build takes several minutes. Upgrading: `git pull && docker compose up -d --build --wait`.
