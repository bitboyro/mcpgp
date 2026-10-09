# mcpgp

**Generate MCP servers from OpenAPI contracts.** Configure what an agent can do, build a
package, and ship it to your customers. They run it on their own infrastructure, connected
only to their own systems. It never calls back to the platform or to you.

> Status: **early implementation.** Specs, examples and process are in place; the JSON Schemas
> (milestone M1) are being built. See [docs/process/roadmap.md](docs/process/roadmap.md).

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
- Guides: [building in a pipeline](docs/guides/ci-pipeline.md)
- [Security & threat model](docs/security.md)
- [Decisions (ADRs)](docs/decisions)
- [UI design](docs/design/ui.md)
- [Development process](docs/process/development.md) · [Contributing](CONTRIBUTING.md)

## License

[MIT](LICENSE)
