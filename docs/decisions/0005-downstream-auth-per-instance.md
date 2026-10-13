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

### Addendum (2026-10-13): credentials from the request

A client may send its own credential **for the API** in a configured header (`fromRequest`,
auth.md §2.4), when the definition allows it. This keeps the decision above: the client's sign-in to
the MCP server is still never forwarded; the header is a separate downstream credential, chosen by
the user in the client's configuration (not by the model), read per request for one instance and
never kept. An instance may list several credentials, tried in order, so a service account can take
over when the header is missing.

## Consequences

- The generated config schema offers only supported types.
- Tools an instance's credentials can't use (e.g. missing scope) are hidden or explained, per policy.
