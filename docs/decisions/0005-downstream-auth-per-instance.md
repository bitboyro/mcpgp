# 0005. Downstream auth chosen per instance; no token passthrough

- Status: Accepted
- Date: 2026-10-09

## Context

One build is delivered to many customers whose target servers use different auth (basic, API key,
OAuth, mTLS). The MCP specification forbids passing the client's token through to upstream APIs.

## Decision

The build declares the **supported** downstream auth types per API (pre-filled from the specs'
`securitySchemes`). Each instance in the runtime config picks one; mTLS can be combined with any.
Identity is a service account per instance; per-user identity (token exchange, URL elicitation) is P2.
Inbound tokens are never forwarded.

## Consequences

- The generated config schema offers only supported types.
- Tools an instance's credentials can't use (e.g. missing scope) are hidden or explained, per policy.
