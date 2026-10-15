# Deploying the platform

The platform's container image and Docker Compose files. Step by step:
[../docs/guides/self-hosting.md](../docs/guides/self-hosting.md). What each part is:
[../docs/deployment.md](../docs/deployment.md).

```bash
cd deploy
chmod 700 secrets && openssl rand -base64 24 > secrets/admin_password && chmod 644 secrets/admin_password
cp .env.example .env
docker compose up -d --build --wait                                      # file store
docker compose -f compose.yaml -f compose.postgres.yaml up -d --build --wait   # or PostgreSQL (also needs secrets/db_password)
```
