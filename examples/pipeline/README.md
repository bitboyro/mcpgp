# Example: a definitions repository with CI

What a team's repo looks like when it builds MCP servers in a pipeline. The definitions themselves
are the ones in [../platform](../platform); this folder adds the repo-level pieces, and its config
points at those definitions, so the commands below work here as they would in a real repo:

| File | Purpose |
|---|---|
| [.mcpgp/config.yaml](.mcpgp/config.yaml) | Pins the CLI version, lists the servers, the workspace, output and signing |
| [.github/workflows/mcp.yml](.github/workflows/mcp.yml) | GitHub Actions: PR checks and tagged releases, with the CLI image |
| [.gitlab-ci.yml](.gitlab-ci.yml) | The same for GitLab CI |
| [../platform/orders/tests/smoke.yaml](../platform/orders/tests/smoke.yaml) | Tool test cases run against the mock target |

```bash
cd examples/pipeline
mcpgp validate --all
mcpgp test --all
MCPGP_SIGNING_KEY="$(cat mcpgp.key)" mcpgp build --all --frozen-lock   # a key from mcpgp keygen
MCPGP_SIGNING_KEY="$(cat mcpgp.key)" mcpgp sign dist/
mcpgp verify dist/ --key mcpgp.pub
```

In a real repo, `servers/orders/` would contain `orders.mcpgp.yaml`, `contracts/`, `overrides/`,
`tests/` and `mcp-names.lock` as in [../platform/orders](../platform/orders).

Guide: [docs/guides/ci-pipeline.md](../../docs/guides/ci-pipeline.md).
