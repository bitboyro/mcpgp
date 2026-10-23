# Agents on the platform

Agents build MCP servers through the same API as people, with their own identity and token. The
easiest way in is the platform's own MCP server: mcpgp builds it from the platform's OpenAPI document
(the project is [platform/mcp](https://github.com/bitboyro/mcpgp/tree/20a8ca16bec3577bce49dd57152143bfcb4c69ae/platform/mcp)), and the agent runs it like any other mcpgp server.
The platform applies the agent's scopes, quotas and approvals to every call
([spec/platform-api.md §3, §5](../spec/platform-api.md#3-identities-sign-in-and-scopes)).

The platform's own server is also the first one on **All servers** (`mcpgp-platform`, made by the
platform and updated with every release). **Connect an agent** there, or on **API & agents**, does
steps 1 and 2 in one go: it creates the agent and its first token, shows what to paste into Claude
Code, Claude Desktop, Cursor or VS Code, and says when the agent first calls. Only admins create
agents; others are asked to have an admin do it. To change what agents get (hide tools, add notices),
make a variant of `mcpgp-platform`.

## 1. An identity and a token

An administrator creates the agent on **API & agents** (or `POST /api/v1/identities` with
`{ kind: agent, name, role }`); its first token is shown once. Roles: `builder` uploads versions,
edits and builds; `releaser` may also ask for releases. An agent's release always waits for a person.

## 2. The package

Download it from **API & agents** ("Download mcpgp-platform.zip"), or:

```bash
curl -OJ -H "Authorization: Bearer $MCPGP_TOKEN" https://mcpgp.acme.internal/api/v1/platform-mcp
```

It is signed with the platform's key when one is configured (check it as for any package:
`minisign -V -p SIGNING-KEY.pub -m SHA256SUMS -x SHA256SUMS.sig`). Next to the jar, `mcpgp-platform.yaml`:

```yaml
instance:
  baseUrl: https://mcpgp.acme.internal
  auth: { type: bearer, token: "${env:MCPGP_TOKEN}" }
```

Then register it with the agent's client, for example:

```bash
claude mcp add mcpgp-platform -e MCPGP_TOKEN=<the agent's token> -- java -jar mcpgp-platform.jar --config mcpgp-platform.yaml
```

## 3. What the agent gets

About 45 tools, all prefixed `platform_`: servers and versions (`platform_server_list`,
`platform_version_add`, `platform_server_edit`, `platform_manifest_get`, `platform_version_validate`),
builds (`platform_build_start`, `platform_build_log_get`, `platform_artifact_download`,
`platform_build_release`), the library, identities and tokens, approvals and activity; plus
`platform_explain_tool` and `platform_audit_list`. People's sign-in, passwords and the store's export and
import are left out. Deleting or archiving a build, archiving a server, revoking a token and rejecting a
request ask for confirmation first.

A typical loop: `platform_server_list` → `platform_server_edit` with `dryRun` to see what a change
does → `platform_server_edit` to save it → `platform_build_start` → `platform_build_log_get` →
`platform_build_release` (which becomes an approval a person decides on **Activity**).

## 4. Guardrails

Quotas per agent (builds and versions per hour, `guardrails` in `platform.yaml`) answer `429
QUOTA_EXCEEDED` when exceeded. Agents never decide approvals, delete builds, or change identities or
settings, whatever their scopes. Everything an agent does is in the activity log, as `agent:<name>`.
