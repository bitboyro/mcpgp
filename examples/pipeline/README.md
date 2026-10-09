# Example: a definitions repository with CI

What a team's repo looks like when it builds MCP servers in a pipeline. The definitions themselves
are the ones in [../platform](../platform); this folder adds the repo-level pieces:

| File | Purpose |
|---|---|
| [.mcpgp/config.yaml](.mcpgp/config.yaml) | Pins the CLI version, lists the servers, output and signing |
| [.github/workflows/mcp.yml](.github/workflows/mcp.yml) | GitHub Actions: PR checks and tagged releases |
| [.gitlab-ci.yml](.gitlab-ci.yml) | The same for GitLab CI |
| [servers/orders/tests/smoke.yaml](servers/orders/tests/smoke.yaml) | Tool test cases run against the mock target |

In a real repo, `servers/orders/` would contain `orders.mcpgp.yaml`, `contracts/`, `overrides/` and
`mcp-names.lock` as in [../platform/orders](../platform/orders).

Guide: [docs/guides/ci-pipeline.md](../../docs/guides/ci-pipeline.md).
