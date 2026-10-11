# Spec: runtime config (operator side)

Status: **draft v1** · Owner: runtime

The operator's config for a delivered server, in **YAML or JSON**: `<server>.yaml`, `<server>.yml` or
`<server>.json` next to the jar (or mounted into the container), or any file given with `--config`;
the format follows the file extension. Secrets go in `.env` or a keychain. The two formats are
equivalent: every example in this spec can be written as JSON with the same keys. If more than one
of the default file names exists, startup fails rather than guessing. `init` writes YAML by default,
JSON with `--format json`. Every package ships `server.config.schema.json`,
**generated for that build** from the generic [runtime-config.schema.json](../../schemas/src/main/resources/schemas/runtime-config.schema.json): it only contains what the build supports.

## 1. Precedence

1. Build defaults (from `runtimeDefaults` in the definition)
2. Config file (optional: a server can be configured entirely from environment variables)
3. Environment variables (§1.1)
4. CLI flags, e.g. `--server.port 8080`

Values outside what the build allows (`allowed` lists, upper limits) are rejected at startup.

### 1.1 Environment variable names

Every config key can be set as an environment variable:

- Prefix: the server name in upper snake case plus `_` (`orders-mcp` → `ORDERS_MCP_`).
- Path segments joined by `__` (double underscore); key names in upper snake case
  (`publicUrl` → `PUBLICURL`, `timeoutSeconds` → `TIMEOUTSECONDS`).
- **List items** by zero-based index: `<LIST>_<n>__<FIELD>`.

```bash
ORDERS_MCP_SERVER__PORT=8080
ORDERS_MCP_SERVER__PUBLICURL=https://orders-mcp.acme.internal

ORDERS_MCP_INSTANCES_0__ID=prod-eu
ORDERS_MCP_INSTANCES_0__BASEURL=https://orders-eu.acme.internal/api/v2
ORDERS_MCP_INSTANCES_0__AUTH__TYPE=basic
ORDERS_MCP_INSTANCES_0__AUTH__USERNAME=svc-mcp
ORDERS_MCP_INSTANCES_0__AUTH__PASSWORD=…
ORDERS_MCP_INSTANCES_0__TLS__CA__BASE64=LS0tLS1CRUdJTi…

ORDERS_MCP_INSTANCES_1__ID=partner-x
…
```

Rules:
- Indexes must be contiguous from 0; a gap fails startup naming the missing index.
- In multi-API servers, instances are addressed per API: `ORDERS_MCP_APIS__ORDERS__INSTANCES_0__ID`.
- If the config file also defines the list, the **whole list** comes from one source: environment
  variables for a list replace the file's list (no item-by-item merging, which would be ambiguous).
  Scalar keys override individually.
- Single-instance builds use `ORDERS_MCP_INSTANCE__…` (no index).
- Environment variables set directly like this count as references for the secret rule in §2.
- A variable with the server's prefix that matches no setting is logged as a warning and ignored
  (it may be a value used by a `${env:…}` reference); it never stops startup.
- CLI flags use the same keys with dots: `--server.port 8080`, `--instances.0.timeoutSeconds 60`.
  Unlike environment variables, a flag changes only the field it names, inside the existing list
  item; it never replaces the list.

## 2. Secrets

### 2.1 References

A secret value is given as a reference, never written in the file. Inside YAML flow mappings `{ … }`,
quote references: `"${env:NAME}"`.

| Reference | Meaning |
|---|---|
| `${env:NAME}` | Environment variable (`.env` next to the config is loaded automatically) |
| `${file:./path}` | File content, trimmed (e.g. Docker or Kubernetes secrets mounted as files) |
| `${keychain:name}` | OS keychain (P1) |
| `${vault:path#key}` | Vault (P2) |

### 2.2 Secret fields

The generated config schema marks these fields as secret (`"x-mcpgp-secret": true`):

| Field | Where |
|---|---|
| `auth.password`, `auth.token`, `auth.value` (API key), `auth.clientSecret` | instances |
| `tls.password`, `tls.clientKey.base64`, `tls.clientCert.base64` | instances |
| `server.auth.keys[].value` | server |
| tokens and passwords in pack config (issue tracker token, …) | packs |
| `telemetry.otlp.headers` values | telemetry |

`tls.ca.base64` is not a secret (CA certificates are public) but is still redacted in logs for brevity.

### 2.3 Enforcement

- A **plain value in a secret field is rejected at startup**, naming the field (never the value).
  Allowed: a reference (§2.1) or a direct environment variable (§1.1).
- Escape hatch for local experiments: `allowInlineSecrets: true` at the top of the config. The server
  then starts, logs a warning at every start, records `inlineSecrets: true` in the `server.start`
  audit event, and `doctor` reports it as a failing check.
- Secret fields are **always redacted** in logs, audit events, recordings, error messages and the
  operator commands' output, whatever their source.
- Resolved secret values live only in memory; they are never written back to disk by `init` or
  `instance add` (those write references and put values in `.env` or the keychain).

## 3. Shape

### 3.1 Single-instance build

```yaml
server:                       # HTTP builds only; omitted in stdio-only builds
  transport: http             # stdio | http
  host: 127.0.0.1
  port: 8080
instance:
  baseUrl: https://orders.acme.internal/api/v2
  contract: auto              # auto | "1.0" | "2.0"
  auth:
    type: basic
    username: ${env:ORDERS_USER}
    password: ${env:ORDERS_PASS}
audit:
  file: { path: ./audit/audit.jsonl }
```

### 3.2 Multi-instance build

```yaml
instances:
  - id: prod-eu               # the value agents pass as `instance`
    baseUrl: https://orders-eu.acme.internal/api/v2
    contract: auto
    auth:
      type: oauth2ClientCredentials
      tokenUrl: https://login.acme.com/oauth2/token
      clientId: ${env:EU_CLIENT_ID}
      clientSecret: ${env:EU_CLIENT_SECRET}
      scopes: [orders.read]
  - id: partner-x
    baseUrl: https://orders.partner-x.example/api/v2
    contract: "1.0"
    readOnly: true
    auth:
      type: basic
      username: ${env:CX_USER}
      password: ${env:CX_PASS}
```

In multi-API servers, instances are grouped by API: `apis: { orders: { instances: [...] }, jira: { instances: [...] } }`.

## 4. Reference

| Key | Default | Notes |
|---|---|---|
| `server.transport` | build default | `stdio` · `http` (only those the build includes) |
| `server.host` / `port` | `127.0.0.1` / `8080` | |
| `server.publicUrl` | — | URL clients use; required for OAuth metadata |
| `server.basePath` | `/mcp` | |
| `server.tls` | none | `{ cert, key }` or `proxy` (TLS ends at a reverse proxy) |
| `server.trustProxy` | `[]` | addresses whose `X-Forwarded-*` headers are trusted |
| `server.allowedOrigins` | `[]` | Origin check for browser clients (DNS rebinding protection) |
| `server.outboundProxy` | none | HTTP proxy for calls to instances |
| `server.management.port` | none | health + metrics on a separate port |
| `server.auth.mode` | `none` | `none` · `static` · `oauth`, within the build's `allowed` list |
| `server.auth.keys[]` | — | `static` mode: `{ name, value }` per client key; `value` is a secret field |
| `server.auth.issuer` | — | `oauth` mode: authorization server (see [auth.md](auth.md) §1.1); needs `server.publicUrl` |
| `server.auth.jwksUri` | from the issuer's metadata | `oauth` mode: the issuer's JWKS, when its metadata doesn't name it |
| `server.auth.introspection` | — | `oauth` mode, opaque tokens: `{ url, clientId, clientSecret }` (RFC 7662) |
| `server.auth.scopes` | — | `oauth` mode: scope → tool selection mapping |
| `server.auth.allowInsecure` | `false` | required for `none` over HTTP on a non-loopback address |
| `instance` / `instances[]` | — | see §3 |
| `instances[].id` | — | `[a-z0-9-]{1,40}`; unique |
| `instances[].baseUrl` | — | the complete address of the target server (§4.1); must match the build's base-URL allowlist if one is set |
| `instances[].contract` | `auto` | `auto` or a shipped version |
| `instances[].auth.type` | — | one of the build's supported types |
| `instances[].tls` | system trust | trust anchors, client certificate, hostname check: see §5 |
| `instances[].timeoutSeconds` | `30` | |
| `instances[].readOnly` | `false` | hides write tools for this instance |
| `audit.file.enabled` | `true` | |
| `storage.root` | `~/.<server>/` | where everything the server writes lives (§4.2); env `<SERVER>_STORAGE__ROOT` |
| `audit.file.path` | `<storage.root>/audit/audit.jsonl` | |
| `audit.file.rotate` | `daily` | `daily` · `hourly` · `size:<MB>` |
| `audit.file.retainDays` | `30` | |
| `audit.arguments` | `redacted` | `none` · `redacted` · `hashed` |
| `telemetry.enabled` | `false` | also honours `OTEL_SDK_DISABLED` |
| `telemetry.exporter` | `otlp` | `otlp` · `prometheus` (HTTP only) |
| `telemetry.otlp.endpoint` / `protocol` | `http://localhost:4317` / `grpc` | `http/protobuf`: default `http://localhost:4318` |
| `telemetry.signals` | `[traces, metrics]` | |
| `packs.recording.retainDays` | `14` | recordings older than this are removed at start (packaging §4.1) |
| `telemetry.serviceName` | server name | |
| `logging.level` | `info` | stdio: stderr or file only |
| `allowInlineSecrets` | `false` | local experiments only; see §2.3 |
| `errors.detail` | `full` | `full` · `summary` (§4.5) |
| `packs.<id>` | — | pack config; packs needing config register only when it is set |
| `files.base64.enabled` / `maxKb` | `true` / `1024` | inline base64 file inputs (§6) |
| `files.path.enabled` / `root` / `maxKb` | `false` / `<storage.root>/uploads` / `10240` | file inputs by path on the server host (§6) |

### 4.1 Target server address (`baseUrl`)

The address contains everything; there is no separate port field.

| Written as | Means |
|---|---|
| `orders.acme.internal` | `https://orders.acme.internal:443` + the contract's base path |
| `orders.acme.internal:8443` | `https://orders.acme.internal:8443` + the contract's base path |
| `https://orders.acme.internal:8443/api/v2` | used exactly as written |
| `http://orders.test.internal` | `http://…:80` + base path; plain HTTP is allowed only when written explicitly, and logged as a warning |

- No scheme → `https`. No port → `443` for https, `80` for http. Any other port must be written.
- No path → the contract's base path from the spec is appended (e.g. `/api/v2`); with a path, nothing
  is appended.
- An address that doesn't parse stops startup, naming the instance.

### 4.2 Storage root

Everything the server writes lives under one root, set by the operator:

```
<storage.root>/                 env <SERVER>_STORAGE__ROOT · default ~/.<server>/
├─ audit/audit.jsonl            audit file (rotated)
├─ logs/                        operational logs (stdio servers, §4.3)
├─ recordings/                  recording pack sessions
├─ issues/                      issue-tracker pack, `local` destination
├─ skills/history/              skills pack versions
└─ uploads/                     default root for `files.path` (§6)
```

Each location can still be overridden on its own (`audit.file.path`, `files.path.root`, …). The
directory is created with owner-only permissions; config and `.env` usually live next to it.

### 4.3 Health and logs

- **Health** (HTTP servers, on the management port or the main port when none is set; with a
  management port, health and metrics answer only there and the MCP endpoint only on the main port):
  - `GET /health/live` → `200 {"status":"up"}` while the process runs.
  - `GET /health/ready` → `200` when configuration is loaded and at least one instance is reachable;
    `503` otherwise. Body: `{"status":"up|down|degraded","instances":{"prod-eu":"up","partner-x":"down"}}`.
  - `GET /metrics` when `telemetry.exporter: prometheus` (audit-and-errors §4).
- **Operational logs** (separate from the audit file): one JSON object per line with `ts`, `level`,
  `logger`, `message`, `cid`; levels `error`, `warn`, `info`, `debug`. HTTP servers log to stderr
  (container convention); stdio servers log to `<storage.root>/logs/` (or stderr if set), **never
  to stdout**. Secrets are redacted as everywhere else.

### 4.4 Contract detection

For instances with `contract: auto`, when the definition gives a detection rule (`apis[].detect`):

1. At startup (and on reload) the server calls the detection endpoint and matches the reported
   version against the shipped contracts.
2. Match → that contract is used.
3. No rule, endpoint unreachable, or a version that matches no shipped contract → behaviour from the
   definition's `apis[].detect.onFailure`:
   - `useDefault` (default): use the default contract, log a warning, record it in the audit
     `server.start` / `config.reload` event, and show it in `doctor` and `instances_list`;
   - `refuse`: this instance is unavailable (others keep working) until detection succeeds.
4. A pinned contract (`contract: "1.0"`) skips detection; if a rule exists, a mismatch is a warning.

### 4.5 Error detail

How much of an upstream error body reaches the agent:

```yaml
errors:
  detail: full        # full (default) | summary
```

- `full`: status, error code and the upstream body (redacted), as returned. Easiest to debug.
- `summary`: status, error code and one short line: the `title`/`detail` of an
  `application/problem+json` body, or the first line of the body, at most 200 characters.
- In both cases the **full** upstream body is kept in the audit file (redacted), so nothing is lost
  for troubleshooting.

### 4.6 Reload

The server watches its config file (and `.env`, and `path` certificate material) and reloads on change;
`<server> reload` and `SIGHUP` trigger the same.

| Changes live | Needs a restart |
|---|---|
| instances (add, remove, change), credentials, TLS material, contract selection, `errors`, `files`, `audit` retention, log level | transport, host, port, inbound auth mode, storage root |

A reload that fails validation is rejected as a whole; the running config stays in effect and the
error is logged and audited. `<server> reload` leaves a request file (`reload.request`) in the
storage root, which the running server picks up; this works on every platform (`SIGHUP` only on
Unix-like systems). Calls already running finish with the old config. Changes that need a restart
are logged as such and take effect at the next start. Added or removed instances update the `instance` enum and send
`notifications/tools/list_changed` to connected clients.

## 5. TLS and certificate material

### 5.1 Certificate material

Anywhere the config takes a certificate, a bundle or a key, it accepts **exactly one** of:

| Form | Example | Use when |
|---|---|---|
| `path` | `path: ./certs/acme-ca.pem` | a file on the server host; supports rotation without restart (§5.4) |
| `base64` | `base64: LS0tLS1CRUdJTi…` | containers, secret managers, single-file configs |

Either value may itself be a secret reference: `base64: ${env:ACME_CA_B64}`, `path: ${env:CA_PATH}`.
PEM files may hold one certificate or a bundle; client certificates may be PEM (with a separate key)
or PKCS#12. Giving both forms, or neither where one is required, fails startup naming the instance and
the field, never the content.

### 5.2 Instance TLS settings

```yaml
instances:
  - id: prod-eu
    baseUrl: orders-eu.acme.internal:8443
    tls:
      ca: { path: ./certs/acme-ca.pem }         # or { base64: … }
      trust: ca-only                            # ca-only | ca-and-system | system
      clientCert: { path: ./certs/client.p12 }  # optional, for mTLS
      clientKey: { path: ./certs/client.key }   # only when clientCert is PEM
      password: ${env:CLIENT_P12_PASS}
      insecureSkipHostnameVerify: false
```

| Key | Default | Notes |
|---|---|---|
| `tls.ca` | none | trust anchors for this instance only |
| `tls.trust` | `ca-only` when `ca` is set, else `system` | `system` = the runtime's default trust store; `ca-and-system` = both |
| `tls.clientCert` / `clientKey` / `password` | none | mTLS; combinable with any auth type |
| `tls.insecureSkipHostnameVerify` | `false` | skips **only** the hostname (CN/SAN) check; the chain is still validated; logged as a warning at startup |

### 5.3 Truststore rules

- **One in-memory truststore per instance**, built from that instance's material. The global trust
  store of the runtime or the operating system is **never modified**.
- **No trust-all mode exists.** The certificate chain is always validated; the only relaxation is the
  hostname check above.
- Instances never share trust: a CA configured for one instance is not trusted for another.
- Certificate material, keys and passwords are **always redacted** in logs, audit, recordings and errors.

### 5.4 Rotation

- `path` material: when a TLS handshake fails and the file content has changed since it was loaded,
  the server rebuilds that instance's client from the new file and retries the call **once**. An
  unchanged file means the error is reported as is. Nothing is fetched from the network.
- `base64` material and environment values are read at startup or on config reload only.

### 5.5 Getting a certificate (trust on first use)

For self-signed or internal-CA servers the operator can let the server fetch the certificate:

- `<server> instance add <id>` and `<server> init`: if **no** material is given and the connection
  fails with an untrusted-certificate error, the server fetches the chain the host presents, shows
  subject, issuer, validity, SHA-256 fingerprint and a **hostname warning** when the certificate
  doesn't cover the host, and saves it (as `path` or `base64`) **only after the operator confirms**
  and a second connection test succeeds. `--yes` confirms in scripts.
- Configured material is never replaced automatically.
- The same fetch is available as a tool in the diagnostics pack (`tls_certificate_fetch`), limited to
  the hosts of configured instances unless the pack config allows any host.

## 6. File inputs

Some operations take files (multipart uploads, binary bodies). Tools receive them in the forms
described in [tools-contract.md](tools-contract.md) §9; the operator decides which forms are allowed:

```yaml
files:
  base64: { enabled: true, maxKb: 1024 }
  path:   { enabled: true, root: ~/.orders-mcp/uploads, maxKb: 10240 }   # default root: <storage.root>/uploads
```

- **base64** is the only form that works for remote clients of an HTTP server.
- **path** refers to the **server host**. It is off by default; its root defaults to `<storage.root>/uploads`: paths are resolved
  inside it, `..` and symlinks leaving it are rejected, only regular readable files under the size cap
  are accepted. Intended for stdio servers running on the user's own machine.
- File contents are never logged; audit records name, size and SHA-256.

## 7. Startup checks

The server refuses to start, with a message naming the key, when:
- the config doesn't match the schema;
- an instance uses an auth type the build doesn't support (`type: none`, no credentials, is always accepted);
- a pinned contract isn't shipped, or `mixedContracts: forbid` is violated;
- `server.auth.mode: none` on a non-loopback HTTP address without `allowInsecure`;
- a secret reference can't be resolved, or a secret field holds a plain value without `allowInlineSecrets`;
- indexed environment variables for a list have gaps;
- certificate material is missing, ambiguous (both forms) or unreadable;
- an address in `baseUrl` that doesn't parse.

`<server> doctor` runs the same checks plus connectivity tests, without starting.

## 8. Operator commands

| Command | Does |
|---|---|
| `<server> init` | Guided setup; writes config and `.env`; tests each instance |
| `<server> reload` | Reload the config now (§4.6) |
| `<server> doctor [--quick]` | Validates config, tests instances, port, public URL (`--quick`: config + liveness only, for health checks) |
| `<server> instance add/test/remove <id>` | Manage instances without editing YAML; `add` offers trust on first use (§5.5) |
| `<server> instance trust <id> [--base64] [--yes]` | Fetch, show and save the certificate an instance presents |
| `<server> client add <claude-code\|claude-desktop\|cursor\|vscode> [--print]` | Register with an MCP client |

Commands run without starting the server, take the same `--config` and `--<key> <value>` options,
and exit `0` (all checks passed), `1` (a check failed) or `2` (usage). `doctor` marks each check
`✓`, `!` (worth a look) or `✗` (fails); `allowInlineSecrets` is always a failing check.

`init` asks for the transport, client keys (HTTP `static` mode: a key is generated and shown once)
and the instances, writes the config with `${env:…}` references and the values to `.env`
(owner-only), then tests each instance. `instance add` / `instance remove` edit the config in place:
YAML is edited as text, so comments and layout elsewhere stay; a `.bak` copy is kept, and a change
after which the config doesn't load is undone. Secret variables are named `<INSTANCE>_<FIELD>`
(`PROD_EU_PASSWORD`), never with the server's own prefix.

`instance trust` never edits the config file: it saves the confirmed chain as
`certs/<id>.pem` next to the config (or prints it with `--base64`), connects again with only that
material, and prints the lines to add. An instance that already has `tls.ca` is refused.

Examples: [examples/client](../../examples/client).

## 9. Security considerations

- Secrets: references only, plain values rejected, always redacted, never written back (§2).
- TLS: one truststore per instance, no trust-all, hostname relaxation only explicitly (§5).
- File inputs by path are off by default and confined to a root (§6).
- HTTP without inbound auth binds to loopback only unless `allowInsecure` (§4, [auth.md](auth.md)).
- `errors.detail: summary` limits how much upstream content reaches the model (§4.5).
- Everything the server writes is under one owner-only storage root (§4.2).

See [../security.md](../security.md).
