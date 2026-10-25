# Spec: authentication and authorization

Status: **draft v1** · Owner: runtime · Decisions: [ADR-0005](../decisions/0005-downstream-auth-per-instance.md)

Two independent sides:

```
MCP client ──(inbound)──► MCP server ──(upstream)──► target server
```

Tokens never cross from one side to the other (no token passthrough). A client may send its own
credential *for the API* in a separate, configured header (§2.4); that is an upstream credential,
never the client's sign-in to the MCP server.

## 1. Inbound (client → MCP server)

Applies to HTTP transports. stdio servers run as the local user and have no inbound auth. Before
auth, requests with an `Origin` header not in `server.allowedOrigins` are refused with `403`
(DNS-rebinding protection); requests without `Origin` (non-browser clients) pass this check.

| Mode | Standard | Behaviour |
|---|---|---|
| `none` | — | Only on loopback, or with `allowInsecure: true` (runtime-config §4) |
| `static` | RFC 6750 bearer usage | `Authorization: Bearer <key>`; keys from `server.auth.keys[]`, compared in constant time; a missing or unknown key → `401` with `WWW-Authenticate: Bearer`; the key's `name` is the `actor` in audit events |
| `oauth` | MCP Authorization spec; OAuth 2.1; RFC 9728; RFC 8707; RFC 6750 | The server is an OAuth **resource server** (below) |

### 1.1 `oauth` mode

Needs `server.auth.issuer` and `server.publicUrl` (the server refuses to start without them). The
**resource** is `publicUrl` + `basePath`, e.g. `https://orders-mcp.acme.internal/mcp`.

- **Protected resource metadata (RFC 9728)** at `/.well-known/oauth-protected-resource` with the
  resource's path appended (`/.well-known/oauth-protected-resource/mcp`; the bare path answers too):
  `resource`, `authorization_servers` (= `server.auth.issuer`), `scopes_supported` (the keys of
  `server.auth.scopes`), `bearer_methods_supported: ["header"]`.
- **Unauthenticated request** → `401` with
  `WWW-Authenticate: Bearer resource_metadata="<that URL>"`, which starts the client's sign-in. An
  invalid token → `401` with `error="invalid_token"` as well; an authorization server that can't be
  reached to check it → `503`.
- **Token validation:**
  - JWT access tokens (`typ` `JWT`, `at+jwt` or none): the signature with the issuer's JWKS, from its
    RFC 8414 metadata (`/.well-known/oauth-authorization-server`, path appended) or else its OpenID
    metadata, or `server.auth.jwksUri`; cached, and fetched again for an unknown key. Only asymmetric
    algorithms (RS, PS, ES, EdDSA); `none` and shared-secret (`HS*`) tokens are refused. `iss` must
    be the issuer, `exp` / `nbf` hold with 60 s skew, and **`aud` must name the resource** (RFC 8707).
    Scopes from `scope` (space-separated) or `scp`.
  - Opaque tokens: token introspection (RFC 7662) when `server.auth.introspection` is configured
    (`clientId` / `clientSecret` as HTTP Basic); `active` must be true, `aud` (when present) must name
    the resource; answers are cached for 60 s (never past `exp`), by the token's hash.
  - `issuer`, `jwksUri` and the introspection URL are `https://`, or `http://` only on loopback.
- **Scopes:** `server.auth.scopes` maps scopes to tool selections: `access` (`read-only`: tools that
  only read; `read-write`: all but destructive ones; `all`) and optionally `tools` (names, `*` as a
  wildcard), e.g. `{ "orders.read": { access: read-only }, "orders.write": { access: read-write } }`.
  A call to a tool the token's scopes don't allow, directly or through `call_tool`, → `403` with
  `WWW-Authenticate: Bearer error="insufficient_scope", scope="<scopes that would allow it>"`
  (RFC 6750 §3.1). `tools/list` shows only allowed tools. Built-in tools (instances, discovery) are
  open to any valid token; without a mapping, a valid token allows every tool.
- The token's `sub` (or `client_id`) is the `actor` in audit events; tokens are never logged.
- The server never forwards the client's token upstream and never accepts tokens issued for another
  audience.

## 2. Upstream (MCP server → target server)

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
| `fromRequest` | — | the client's own credential from a configured request header, as it was sent (§2.4) |
| `tokenExchange` | RFC 8693 (or Microsoft Entra on-behalf-of) | the signed-in user's OAuth token exchanged for one issued for the API (§2.5) |

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
- Token endpoint errors (RFC 6749 §5.2 `error` codes) surface as `AUTH_UPSTREAM` with the `error`
  value, never the secret.
- The token endpoint is reached with the instance's own TLS settings (runtime-config §5); if it uses
  a public CA while the instance trusts only its own, set `trust: ca-and-system`.
- With `clientAuth: basic`, client id and secret are form-encoded before Basic encoding
  (RFC 6749 §2.3.1). Access tokens are redacted like configured secrets.

### 2.2 Per-operation security

OpenAPI security requirements are alternatives (OR) of sets (AND) of schemes with scopes. The
manifest carries them per tool and contract (manifest §5). For each instance at startup:

- The manifest says what each scheme is (`apis[].upstreamAuth.schemes`: `basic`, `bearer`,
  `apiKey`, `oauth2ClientCredentials`, `mtls`, `oauth2` for other OAuth flows and OpenID Connect,
  `other`).
- A credential **covers** a scheme of its kind: `basic`, `apiKey`; `bearer` covers bearer and OAuth
  schemes (a static token may be an OAuth access token); `oauth2ClientCredentials` covers bearer and
  OAuth schemes, with the configured `scopes` ⊇ the required ones when the config lists scopes
  (without, the identity provider grants its defaults); a client certificate (`tls.clientCert`)
  covers `mtls` next to any credential. `fromRequest` and `none` aren't judged: what the client
  sends is known only per call, and an instance without credentials (a mock, a gateway that adds
  them) sends nothing, so the API decides. A scheme the manifest doesn't describe isn't judged
  either.
- A tool is **usable** on an instance if one of its credentials covers every scheme of one
  alternative.
- A tool no instance can use follows `upstreamAuth.unsatisfiedTools`: `hide` (the default: not
  listed, not callable; the startup log lists the hidden tools and what each instance lacks) or
  `error` (listed). A tool some instance can use stays listed; calls for an instance that can't
  return `AUTH_UPSTREAM` naming what's missing (`needs oauth with the scope orders.write`),
  without calling the API.
- **On each call** the first credential of the instance's list that can be used now (§2.4) and
  covers the operation is used, so an instance can hold, say, an OAuth client and a basic login
  and each operation gets the one it accepts.
- Operations with `security: []` are public: no credentials are sent.

### 2.3 Rules for every type

- Credentials are secret fields (runtime-config §2.2): references only, always redacted.
- Credentials are sent only to the instance's own address; redirects to another host drop them.
- `401`/`403` from the target → `AUTH_UPSTREAM` with status and a short reason.

### 2.4 Credentials from the request (`fromRequest`)

The MCP client sends the credential for the API in a request header of its own, set in the client's
configuration from the user's environment or secret store: the model never sees it, and the MCP
server never stores it. Allowed when the definition enables it (`apis[].upstreamAuth.fromRequest`);
HTTP transport only.

```yaml
instances:
  - id: prod
    baseUrl: https://api.example.internal
    auth:
      - type: fromRequest
        header: X-Orders-Authorization   # default X-<Server>[-<Api>][-<Instance>]-Authorization
        as: Authorization                # the header sent to the API (default)
      - type: oauth2ClientCredentials    # used when the request has no X-Orders-Authorization
        tokenUrl: https://login.example.internal/oauth2/token
        clientId: ${env:CLIENT_ID}
        clientSecret: ${env:CLIENT_SECRET}
```

- **`auth` is one credential or a list**, tried in order for every call: a `fromRequest` entry is used
  when the request carries its header, any other entry always. Entries after a non-`fromRequest`
  one are never reached (a startup warning says so). With nothing usable the call fails with
  `AUTH_UPSTREAM`, naming the header; there's no retry with another entry after the API refuses.
- **Per request, never kept**: the value is read from the HTTP request that carries the call (not
  from the MCP session), used for that call, and dropped. It is never written to disk, the cache,
  logs, audit events or recordings; for the duration of the call it is masked (`***`) in everything
  the call produces, upstream error bodies included. The audit records `upstream.auth: fromRequest`.
- **Its own header per instance**: two instances (of any API in the server) can't read the same
  header, so a production credential never reaches test; the default name includes the API id
  when the server has several APIs and the instance id in multi-instance builds
  (`X-Orders-Mcp-Prod-Authorization`). The definition's `fromRequest.header` sets the name for
  single-API, single-instance servers.
- **Not a header with another job**: `Host`, `Content-Type`, `Cookie`, `Origin`, `Accept`,
  `Mcp-Session-Id`, `Mcp-Protocol-Version`, forwarding and tracing headers are refused, and so is
  `Authorization` while the server's own sign-in (`static`, `oauth`) uses it. With inbound `none` the
  server stays on loopback unless `allowInsecure` (§1), since anyone reaching it could relay to the
  API with a credential of their own.
- The value is sent as it came (e.g. `Bearer …`, `Basic …`), only to the instance's own address,
  with redirects not followed (§2.3). Values with line breaks or over 16 KB are refused.

### 2.5 Calling the API as the signed-in user (`tokenExchange`)

For a shared server whose users sign in with OAuth (§1.1): on each call the server exchanges the
user's access token, which it already checked, at the identity provider for a token issued for the
API, and sends that. The API sees who is calling and applies its own permissions. Allowed when the
definition enables it (`apis[].upstreamAuth.tokenExchange: {}`); needs `server.transport: http`
and `server.auth.mode: oauth`.

```yaml
auth:
  - type: tokenExchange
    tokenUrl: https://login.example.com/oauth2/token
    clientId: ${env:EXCHANGE_CLIENT_ID}            # the server's own client at the identity provider
    clientSecret: ${env:EXCHANGE_CLIENT_SECRET}
    clientAuth: basic                              # basic (default) | post
    flavor: rfc8693                                # rfc8693 (default) | entra-obo
    audience: https://api.example.com              # rfc8693: what the token is for; resource: also sent when set
    scopes: [orders.read]                          # entra-obo: required, e.g. api://<app id>/.default
  - { type: bearer, token: ${env:SERVICE_TOKEN} }  # optional: used when a request has no user
```

- **RFC 8693**: `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`, `subject_token` (the
  user's token), `subject_token_type` and `requested_token_type` `…:token-type:access_token`,
  `audience` / `resource` / `scope` when set. **Entra on-behalf-of**: `grant_type=…:jwt-bearer`,
  `assertion` (the user's token), `requested_token_use=on_behalf_of`, `scope`. The server
  authenticates with its client id and secret (HTTP Basic or in the body).
- The user's token goes **only to the token endpoint**, never to the API or anywhere else; the
  client's sign-in itself is still never forwarded (§1).
- Exchanged tokens are kept **in memory only**, per user (a hash of their token) and instance, until
  `expires_in` minus 60 s, at most 1,000; a `401` from the API discards the user's token and the call
  is tried once more with a new exchange. Both tokens are masked in everything the call produces; the
  audit records `upstream.auth: tokenExchange` and the user as `actor`.
- A refused exchange answers `AUTH_UPSTREAM` with the identity provider's `error` (e.g.
  `invalid_grant`). A request without a signed-in user uses the next credential in the list, or
  answers `AUTH_UPSTREAM`.
- For §2.2 it covers bearer and OAuth schemes like `oauth2ClientCredentials`: scopes are judged when
  the config lists them, except a `…/.default` scope (Entra), which stands for every granted one.

## 3. Security considerations

- No token passthrough; audience-bound inbound tokens. Credentials from the request (§2.4) are a
  separate header, for one instance, used once and never kept. Token exchange (§2.5) sends the
  user's token to the identity provider only, and the API gets a token issued for it.
- Credentials only to the instance's own address; dropped on cross-host redirects.
- Constant-time comparison of static keys; token endpoint errors never expose secrets.

See [../security.md](../security.md).
