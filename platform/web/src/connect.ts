// What a person pastes to connect an agent's MCP client to the platform's own server (guides/agents.md).

export type Client = 'claude-code' | 'claude-desktop' | 'cursor' | 'vscode'

export const CLIENTS: [Client, string][] = [['claude-code', 'Claude Code'], ['claude-desktop', 'Claude Desktop'], ['cursor', 'Cursor'], ['vscode', 'VS Code']]

const JAR = '/path/to/mcpgp-platform.jar'
const CONFIG = '/path/to/mcpgp-platform.yaml'

/** mcpgp-platform.yaml, next to the jar: where the platform is, and the token from the environment. */
export function platformConfig(origin: string): string {
  return ['instance:', `  baseUrl: ${origin}`, '  auth: { type: bearer, token: "${env:MCPGP_TOKEN}" }'].join('\n')
}

/** Where the client keeps its MCP servers, and what to put there. */
export function clientSetup(client: Client, token: string): { where: string; text: string } {
  const args = ['-jar', JAR, '--config', CONFIG]
  const stdio = { command: 'java', args, env: { MCPGP_TOKEN: token } }
  switch (client) {
    case 'claude-code':
      return { where: 'Run in a terminal', text: `claude mcp add mcpgp-platform -e MCPGP_TOKEN=${token} -- java ${args.join(' ')}` }
    case 'claude-desktop':
      return { where: 'claude_desktop_config.json', text: JSON.stringify({ mcpServers: { 'mcpgp-platform': stdio } }, null, 2) }
    case 'cursor':
      return { where: '.cursor/mcp.json', text: JSON.stringify({ mcpServers: { 'mcpgp-platform': stdio } }, null, 2) }
    case 'vscode':
      return { where: '.vscode/mcp.json', text: JSON.stringify({ servers: { 'mcpgp-platform': { type: 'stdio', ...stdio } } }, null, 2) }
  }
}

/** A name for a person's first agent: "ana" or "ana@acme.com" → "ana-assistant". */
export function agentName(person: string): string {
  const base = person.split('@')[0].toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '')
  return (base || 'my') + '-assistant'
}
