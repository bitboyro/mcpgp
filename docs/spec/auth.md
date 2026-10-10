# Spec: authentication and authorization

Status: **draft v1** · Owner: runtime · Decisions: [ADR-0005](../decisions/0005-downstream-auth-per-instance.md)

Two independent sides:

```
MCP client ──(inbound)──► MCP server ──(downstream)──► target server
```

Tokens never cross from one side to the other (no token passthrough).

## 1. Inbound (client → MCP server)

Applies to HTTP transports. stdio servers run as the local user and have no inbound auth.

| Mode | Standard | Behaviour |
|---|---|---|
| `none` | — | Only on loopback, or with `allowInsecure: true` (runtime-config §4) |
| `static` | RFC 6750 bearer usage | `Authorization: Bearer <key>`; keys from `server.auth.keys[]`, compared in constant time |
| `oauth` | MCP Authorization spec; OAuth 2.1; RFC 9728; RFC 8707; RFC 6750 | The server is an OAuth **resource server** (below) |

### 1.1 `oauth` mode

- **Protected resource metadata (RFC 9728)** at `/.well-known/oauth-protected-resource`:
  `resource` (= `server.publicUrl` + base path), `authorization_servers` (= `server.auth.issuer`),
  `scopes_supported`, `bearer_methods_supported: ["header"]`.
- **Unauthenticated request** → `401` with
  `WWW-Authenticate: Bearer resource_metadata="<publicUrl>/.well-known/oauth-protected-resource"`,
  which starts the client's sign-in.
- **Token validation:**
  - JWT access tokens: signature via the issuer's JWKS (from its RFC 8414 metadata), `iss`, `exp`,
    `nbf`, and **`aud` must equal the resource** (RFC 8707). Clock skew tolerance 60 s.
  - Opaque tokens: token introspection (RFC 7662) when `server.auth.introspection` is configured.
- **Scopes:** `server.auth.scopes` maps scopes to tool selections, e.g.
  `{ "orders.read": { access: read-only }, "orders.write": { access: read-write } }`. A call to a tool
  the token's scopes don't allow → `403` with `WWW-Authenticate: Bearer error="insufficient_scope",
  scope="<needed>"` (RFC 6750 §3.1). `tools/list` shows only allowed tools.
- The server never forwards the client's token downstream and never accepts tokens issued for another
  audience.

## 2. Downstream (MCP server → target server)

Each instance uses one of the types the build supports, plus optional mTLS. `mtls` is not an
`auth.type` in the runtime config: it is `tls.clientCert` (runtime-config §5.2), allowed only when
the build lists `mtls` among its supported types.

| Type | Standard | Request |
|---|---|---|
| `basic` | RFC 7617 | `Authorization: Basic base64(user:password)`, UTF-8 |
| `bearer` | RFC 6750 | `Authorization: Bearer <token>` (static token) |
| `apiKey` | OpenAPI `apiKey` scheme | in `header`, `query` or `cookie` with the name from the spec's security scheme |
| `oauth2ClientCredentials` | RFC 6749 §4.4 | token from `tokenUrl`, then `Authorization: Bearer <access_token>` |
| `mtls` | RFC 8446 client certificates (+ RFC 8705 when the API binds tokens to certificates) | client certificate per instance (runtime-config §5) |

### 2.1 `oauth2ClientCredentials`

```yaml
auth:
  type: oauth2ClientCredentials
  tokenUrl: https://login.acme.com/oauth2/token
  clientId: ${env:EU_CLIENT_ID}
  clientSecret: ${env:EU_CLIENT_SECRET}
  clientAuth: basic            # basic (client_secret_basic, default) | post (client_secret_post)
  scopes: [orders.read, orders.write]
  audience: null               # optional `resource` / `audience` parameter (RFC 8707), when the IdP needs it
```

- Token requested with `grant_type=client_credentials`, the scopes space-separated.
- Cached per instance until `expires_in` minus 60 s; requested again before expiry.
- On `401` from the target: the token is discarded, a new one requested, and the call retried **once**.
- Concurrent calls share one token request (no stampede).
- Token endpoint errors (RFC 6749 §5.2 `error` codes) surface as `AUTH_DOWNSTREAM` with the `error`
  value, never the secret.
- The token endpoint is reached with the instance's own TLS settings (runtime-config §5); if it uses
  a public CA while the instance trusts only its own, set `trust: ca-and-system`.
- With `clientAuth: basic`, client id and secret are form-encoded before Basic encoding
  (RFC 6749 §2.3.1). Access tokens are redacted like configured secrets.

### 2.2 Per-operation security

OpenAPI security requirements are alternatives (OR) of sets (AND) of schemes with scopes. The
manifest carries them per tool and contract (manifest §5). For each instance at startup:

- A tool is **usable** if one alternative is satisfied by the instance's auth (type matches the
  scheme; for OAuth, configured `scopes` ⊇ required scopes).
- Unusable tools follow `downstreamAuth.unsatisfiedTools`: `hide` (removed from that instance's
  view; in multi-instance builds the tool stays listed and calls for that instance return
  `AUTH_DOWNSTREAM` naming the missing scope) or `error`.
- Operations with `security: []` are public: no credentials are sent.

### 2.3 Rules for every type

- Credentials are secret fields (runtime-config §2.2): references only, always redacted.
- Credentials are sent only to the instance's own address; redirects to another host drop them.
- `401`/`403` from the target → `AUTH_DOWNSTREAM` with status and a short reason.

## 3. Security considerations

- No token passthrough; audience-bound inbound tokens.
- Credentials only to the instance's own address; dropped on cross-host redirects.
- Constant-time comparison of static keys; token endpoint errors never expose secrets.

See [../security.md](../security.md).
