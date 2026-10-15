# mcpgp

**Generate MCP servers from OpenAPI contracts.** Configure what an agent can do, build a
package, and ship it to your customers. They run it on their own infrastructure, connected
only to their own systems. It never calls back to the platform or to you.

> Status: **0.2.7.** The compiler, the runtime and packaging, the CLI and the self-hosted platform
> with its web UI work end to end; formats may still change before 1.0. What's
> next: [docs/process/roadmap.md](docs/process/roadmap.md).

## What it does

```
 OpenAPI contracts ─┐
 (one or more APIs, │     ┌──────────────┐      ┌──────────────────────┐      ┌───────────────────┐
  several versions) ├───► │  Definition  │ ───► │  mcpgp build         │ ───► │  Package           │
 Rules, packs,      │     │ *.mcpgp.yaml │      │  (CLI, web, API)     │      │  jar · .mcpb ·     │
 naming, auth ──────┘     └──────────────┘      └──────────────────────┘      │  Docker · signed   │
                                                                               │  docs · schema     │
                                                                               └─────────┬─────────┘
                                                                                         │ delivered
                                                                               ┌─────────▼─────────┐
                                                                               │ Customer runs it   │
                                                                               │ against their own  │
                                                                               │ target servers     │
                                                                               └───────────────────┘
```

- **One definition, many outputs.** A versioned, multi-file definition describes the server.
  The web UI, the CLI and the API all edit the same files.
- **Built for real APIs.** Several contract versions per API (Swagger 2.0, OpenAPI 3.0/3.1),
  several APIs per server, override rules for names and descriptions, curated or generated tool names.
- **Single or multi-instance.** Customers point the server at one or many target servers.
  In multi-instance builds, `instance` is a required parameter on every tool.
- **Independent of the platform.** The delivered package runs entirely at the customer and never
  calls back to the platform or to you.
- **Agents can build servers too.** The platform has its own REST API and MCP server.

Java first; other languages later, driven by a language-neutral manifest.

## Getting started

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
Building in CI: [docs/guides/ci-pipeline.md](docs/guides/ci-pipeline.md).

**The platform** (web UI, REST API, builds for a team), with Docker Compose; the image writes its own
setup (compose files, config, generated secrets) into a folder, so nothing else is needed on the host:

```bash
mkdir mcpgp && cd mcpgp
docker run --rm -it --user "$(id -u):$(id -g)" -v "$PWD":/setup docker.io/<account>/mcpgp-platform:<version> setup
docker compose run --rm platform check       # what would go wrong, before starting
docker compose up -d --wait                  # then open the address you gave, user admin
```

PostgreSQL, TLS, company sign-in and backups: [docs/guides/self-hosting.md](docs/guides/self-hosting.md).

**Building from source** (Java 21, Maven 3.9): `mvn verify`; the CLI is then
`cli/target/mcpgp-cli-<version>-exec.jar`, the platform `platform/target/mcpgp-platform-<version>-exec.jar`.

## Two sides

| | **Platform side** (you, the builder) | **Client side** (your customer, the operator) |
|---|---|---|
| Edits | `*.mcpgp.yaml` definition | `<server>.yaml` runtime config + `.env` |
| Decides | Contracts shipped, tools, names, sign-in types allowed, formats, packs | Target servers, credentials, host/port, audit, telemetry |
| Tools | Web UI, `mcpgp` CLI, REST API, platform MCP server | `<server> init`, `doctor`, `instance add`, local admin page |
| Examples | [examples/platform](examples/platform) | [examples/client](examples/client) |

## Documentation

Start at [docs/README.md](docs/README.md).

- [Concepts & glossary](docs/concepts.md)
- [Requirements](docs/requirements.md): every setting, by concern and priority
- [Architecture](docs/architecture.md)
- Specifications: [definition format](docs/spec/definition-format.md) ·
  [naming](docs/spec/naming.md) · [runtime config](docs/spec/runtime-config.md) ·
  [tools contract](docs/spec/tools-contract.md) · [audit & errors](docs/spec/audit-and-errors.md) ·
  [packaging](docs/spec/packaging.md) · [compatibility](docs/spec/compatibility.md) · [CLI](docs/spec/cli.md)
- Guides: [building in a pipeline](docs/guides/ci-pipeline.md), [self-hosting the platform](docs/guides/self-hosting.md) ([deployment](docs/deployment.md)), [running the platform](docs/guides/platform.md), [agents on the platform](docs/guides/agents.md), [writing an extension](docs/guides/extensions.md)
- [Security & threat model](docs/security.md)
- [Decisions (ADRs)](docs/decisions)
- [UI design](docs/design/ui.md)
- [Development process](docs/process/development.md) · [Contributing](CONTRIBUTING.md)

## License

[MIT](LICENSE)
