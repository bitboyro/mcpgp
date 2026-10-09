# Registering the server with MCP clients

Preferred: let the server do it (`orders-mcp client add <client>`; `--dry-run` shows the change,
`--print` only prints it). The files here are what it writes.

## Claude Code

```bash
# stdio (local jar)
claude mcp add orders -- java -jar /opt/orders-mcp/orders-mcp.jar --config /opt/orders-mcp/orders-readonly.yaml
# HTTP (shared server)
claude mcp add --transport http orders https://orders-mcp.acme.internal/mcp
```

For a project, the same entry can live in `.mcp.json` (see [claude-code.mcp.json](claude-code.mcp.json)).

## Claude Desktop

Double-click `orders-mcp-<version>.mcpb`, or add the stdio entry from
[claude_desktop_config.json](claude_desktop_config.json) to
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or
`%APPDATA%\Claude\claude_desktop_config.json` (Windows). Remote HTTP servers are added in the app's
connector settings.

## Cursor

[cursor.mcp.json](cursor.mcp.json) → `~/.cursor/mcp.json` or `<project>/.cursor/mcp.json`.

## VS Code

[vscode.mcp.json](vscode.mcp.json) → `<project>/.vscode/mcp.json` (top-level key is `servers`).

Client config formats change over time; the `client add` adapters are versioned with the server.
No secrets ever go into these files: the server reads its own config and `.env`.
