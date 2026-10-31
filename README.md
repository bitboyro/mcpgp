<img src="platform/web/public/brand/mcp-gp-logo.svg" alt="MCP GP" width="238">

**Give AI assistants access to your API—without writing an MCP server by hand.**

MCP GP generates servers for the **Model Context Protocol (MCP)**, a standard that lets
AI applications discover and call tools. An MCP server built with MCP GP translates
those tool calls into requests to your existing HTTP API.

For example, an orders API can become tools for finding an order or checking its status.
You choose which operations are available, how they are described, and which access
restrictions apply.

## How it works

1. **Bring your API specification.** Use an OpenAPI or Swagger file describing your API's
   endpoints, inputs and responses.
2. **Define the server.** Select operations and configure tool names, instructions and
   authentication options through the command-line tool or the self-hosted web platform.
   The configuration is stored in YAML files that you can keep in Git.
3. **Build and run it.** Generate a runnable server package, configure the target API address
   and credentials where it runs, and connect an MCP-compatible AI application.

The generated server runs on your infrastructure or your customer's. It needs no connection
back to the MCP GP platform. You can build servers locally with the CLI, use the platform
with your team, or let an agent author them through the platform's own MCP interface.

## Start here

- [Getting started](docs/guides/getting-started.md) — install the CLI or platform and build a server.
- [Examples](examples/README.md) — explore existing server definitions.
- [Documentation](docs/README.md) — guides for building servers, running them and administering the platform.
- [Releases](https://github.com/bitboyro/mcpgp/releases) — downloads and release notes.

[Contributing](docs/contributors/README.md) · [Security](SECURITY.md) · [MIT license](LICENSE)
