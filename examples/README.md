# Examples

Illustrative, not executable yet. They follow the specs in [../docs/spec](../docs/spec) and will be
validated in CI against the JSON Schemas once those exist (milestone M1).

## Platform side (the builder)

| Example | Shows |
|---|---|
| [platform/orders](platform/orders) | Full multi-file definition: two contract versions, rules in several files, packs, multi-instance, lock file |
| [platform/orders-readonly](platform/orders-readonly) | A **variant**: extends `orders`, read only, single instance, desktop bundle |
| [platform/ops-assistant](platform/ops-assistant) | A server combining **three APIs** with discovery exposure |
| [platform/workspace/naming](platform/workspace/naming) | A shared naming policy used by several servers |

## Client side (the operator)

| Example | Shows |
|---|---|
| [client/single-instance](client/single-instance) | Runtime config for `orders-readonly`: one target server, stdio |
| [client/multi-instance](client/multi-instance) | Runtime config for `orders-mcp`: three target servers, mixed auth, HTTP + OAuth inbound |
| [client/multi-api](client/multi-api) | Runtime config for `ops-assistant`: instances per API |
| [client/env-only](client/env-only) | The same configuration as environment variables only (list items by index) |
| [client/docker](client/docker) | Running the HTTP variant with Docker Compose |
| [client/mcp-clients](client/mcp-clients) | Registering the server in Claude Code, Claude Desktop, Cursor, VS Code |

## Pipeline

| Example | Shows |
|---|---|
| [pipeline](pipeline) | A definitions repo with pinned CLI, GitHub Actions / GitLab CI, tool test cases |

## Manifest

| Example | Shows |
|---|---|
| [manifest/orders-mcp.manifest.json](manifest/orders-mcp.manifest.json) | Compiler output for `orders-mcp` (abbreviated: 4 tools) |
