# Secrets for docker compose

One file per secret, mounted into the containers at `/run/secrets/<name>`. Nothing in this folder
except this README is committed (see `.gitignore`).

| File | Used by | Create it with |
|---|---|---|
| `admin_password` | the first administrator (first start only; at least 12 characters) | `openssl rand -base64 24 > admin_password` |
| `db_password` | PostgreSQL and the platform (`compose.postgres.yaml`) | `openssl rand -base64 24 > db_password` |
| `oidc_client_secret` | company sign-in, when `auth.oidc` is on | paste the secret from your identity provider |
| `signing_key` | package signing, when `signing.key` is on | `mcpgp keygen` |

Protect the folder, not the files: `chmod 700 .` and `chmod 644 *`. The containers run as their own
users (the platform as uid 10001, PostgreSQL as its `postgres` user) and read the files as mounted
secrets, so the files must be readable; the folder keeps other people on the host out.
