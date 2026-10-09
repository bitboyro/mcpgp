# Spec: audit events, error codes, telemetry

Status: **draft v1** · Owner: runtime · Schema: [audit-event.schema.json](../../schemas/src/main/resources/schemas/audit-event.schema.json)

The same event format and error codes in every language runtime.

## 1. Audit events

JSON Lines, one event per line, UTF-8. Written to `audit.file.path`. **Never to stdout in stdio mode.**

```json
{"ts":"2026-10-09T10:42:18.311Z","v":1,"type":"tool.call","server":"orders-mcp","build":"1.4.1","actor":{"kind":"user","id":"j.doe","auth":"oauth"},"tool":"orders_order_refund","api":"orders","instance":"prod-eu","contract":"2.0","outcome":"error","code":"UPSTREAM_TIMEOUT","upstream":{"method":"POST","path":"/orders/{id}/refund","status":null},"latencyMs":30004,"args":"redacted","cid":"c-58e1d0"}
```

| Field | Required | Notes |
|---|---|---|
| `ts` | yes | RFC 3339, UTC, milliseconds |
| `v` | yes | event format version, `1` |
| `type` | yes | see §2 |
| `server`, `build` | yes | from the manifest |
| `actor` | when known | `{ kind: user\|client\|system, id, auth }` |
| `tool`, `api`, `instance`, `contract` | for tool events | |
| `outcome` | yes | `ok` · `error` · `denied` |
| `code` | when not ok | §3 |
| `upstream` | for tool events | method, **templated** path, status |
| `latencyMs` | for calls | |
| `args` | for tool events | `"none"`, `"redacted"` (keys with values masked) or `{ "sha256": … }` |
| `cid` | yes | correlation id, also in result `_meta` and outbound `traceparent` |

### Redaction

Always masked regardless of settings: credentials, auth headers, values of fields matching the
build's redaction list (defaults: `password`, `secret`, `token`, `apiKey`, `authorization`).

## 2. Event types

| Type | When |
|---|---|
| `server.start` / `server.stop` | process lifecycle; includes config hash, never config values |
| `session.start` / `session.end` | MCP session (HTTP stateful) or process (stdio) |
| `auth.inbound` | inbound auth decision |
| `tool.call` | every tool call |
| `tool.confirm` | confirmation asked / given / refused |
| `instance.health` | instance status change (healthy, degraded, unreachable) |
| `config.reload` | runtime config or instances reloaded |
| `pack.<id>.<action>` | pack-specific (e.g. `pack.recording.start`) |

## 3. Error codes

| Code | Meaning | Returned as |
|---|---|---|
| `INPUT_VALIDATION` | arguments don't match the schema (or the instance's contract), or a file input is rejected (with a reason) | `isError` |
| `INSTANCE_REQUIRED` / `INSTANCE_UNKNOWN` | missing or unknown `instance` | `isError`, lists valid names |
| `NOT_AVAILABLE_ON_INSTANCE` | operation not in that instance's contract, or instance read-only | `isError` |
| `CONFIRMATION_REQUIRED` | destructive tool needs confirmation | `isError` + `confirm` token |
| `AUTH_INBOUND` | client not allowed | JSON-RPC / HTTP 401/403 |
| `AUTH_DOWNSTREAM` | target server rejected credentials or scope | `isError` |
| `UPSTREAM_4XX` | target returned 4xx (other than auth) | `isError`, status + safe message |
| `UPSTREAM_5XX` | target returned 5xx | `isError` |
| `UPSTREAM_TIMEOUT` | no response within timeout | `isError` |
| `UPSTREAM_UNREACHABLE` | DNS/TCP/TLS failure | `isError` |
| `RESPONSE_PARSE_ERROR` | target returned 2xx with a body that doesn't parse as declared | `isError` |
| `RESPONSE_TOO_LARGE` | over limit even after truncation | `isError` |
| `CONFIG_INVALID` | startup/reload config error | startup failure |
| `INTERNAL` | anything else | `isError`, no internals leaked |

Messages to the model are short, actionable, and never contain stack traces, credentials or full
upstream bodies.

## 4. Telemetry

- OpenTelemetry. **Off by default.** Exporter `otlp` (push) or `prometheus` (HTTP builds).
- Traces: one span per tool call, child span per upstream request; attributes `mcpgp.tool`,
  `mcpgp.instance`, `mcpgp.contract`, `mcpgp.outcome`, `mcpgp.code`.
- Metrics: `mcpgp.tool.calls` (counter), `mcpgp.tool.duration` (histogram), `mcpgp.instance.up` (gauge).
- **Never** arguments, bodies or credentials.
- Storage destinations (S3, Grafana, Datadog…) are configured in an OpenTelemetry Collector, not in
  the server. Builds include an example Collector config in `OPERATIONS.md`.
