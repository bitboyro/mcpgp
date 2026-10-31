# Getting started

**The CLI** (Java 21+): download `mcpgp-cli-<version>.zip` from the
[releases](https://github.com/bitboyro/mcpgp/releases), unzip it and put its `bin/` on your PATH. In
CI, use the image `docker.io/<account>/mcpgp-cli:<version>` instead.

```bash
mcpgp init --from-spec ./openapi.yaml        # a definition project from your spec; prints its file name
mcpgp validate my-api-mcp.mcpgp.yaml
mcpgp test my-api-mcp.mcpgp.yaml             # the server against a mock made from the spec
mcpgp build my-api-mcp.mcpgp.yaml            # dist/<server>-<version>.zip, ready to deliver
```

The examples work as they are: `cd examples/pipeline && mcpgp validate --all && mcpgp test --all`.
Building in CI: [ci-pipeline.md](ci-pipeline.md).

**The platform** (web UI, REST API, builds for a team), with Docker Compose; the image writes its own
setup (compose files, config, generated secrets) into a folder, so nothing else is needed on the host:

```bash
mkdir mcpgp && cd mcpgp
docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<version> setup
docker compose run --rm platform check       # what would go wrong, before starting
docker compose up -d --wait                  # then open the address you gave, user admin
```

PostgreSQL, TLS, company sign-in and backups: [self-hosting.md](self-hosting.md).


Return to the [documentation](../README.md).
