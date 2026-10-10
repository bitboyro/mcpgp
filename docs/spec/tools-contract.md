# Spec: tools contract (what an agent receives)

Status: **draft v1** · Owner: core + runtime · Decision on input shape: [ADR-0006](../decisions/0006-tool-input-schema-shape.md)

## 1. Tool definition

```json
{
  "name": "orders_customer_list",
  "title": "List customers",
  "description": "[Orders] Lists customers in an account, newest first.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "instance": { "type": "string", "enum": ["prod-eu", "partner-x"], "description": "Target Orders API server." },
      "query": {
        "type": "object",
        "description": "Values sent in the URL query string.",
        "properties": {
          "account": { "type": "string", "description": "Account id." },
          "limit":   { "type": "integer", "default": 50, "maximum": 500 }
        },
        "required": ["account"],
        "additionalProperties": false
      }
    },
    "required": ["instance", "query"],
    "additionalProperties": false
  },
  "annotations": { "readOnlyHint": true, "idempotentHint": true, "openWorldHint": true }
}
```

## 2. Input schema shape

**One rule for every tool: inputs are grouped by where they go in the HTTP request.**

```json
{
  "instance": "prod-eu",
  "path":   { "id": "o-1001" },
  "query":  { "notify": true },
  "header": { "X-Request-Source": "agent" },
  "body":   { "amount": 25, "reason": "damaged" }
}
```

| Key | Contains | Present when |
|---|---|---|
| `instance` | target server id (multi-instance builds) | always, in multi-instance builds (§3) |
| `path` | path parameters | the operation has path parameters |
| `query` | query parameters | the operation has query parameters |
| `header` | non-auth header parameters | the operation has such headers |
| `cookie` | non-auth cookie parameters | the operation has such cookies |
| `body` | the request body, with its schema as is | the operation has a request body |

Rules:
- Keys are always these names; a key is omitted when the operation has no inputs of that kind.
  A group is `required` when at least one of its members is required (or the body is required).
- Each group has a short description ("Values placed in the URL path.") so models know where values go.
- Parameter names inside a group are the OpenAPI names, unchanged. Clashes between locations are
  impossible because each location has its own group.
- `body` holds the body schema directly (object, array or scalar). Form and multipart bodies:
  form fields and parts as properties of `body`; file parts use the file object of §9.
- Auth headers, auth query parameters and auth cookies are **never** exposed; the runtime injects
  credentials.
- `readOnly` schema properties are removed from inputs.
- `$ref`s are inlined up to depth 5, then placed in `$defs`.
- JSON Schema 2020-12; `additionalProperties: false` on the top level and on every group.
- The runtime maps each group straight back to the request: no guessing, no renaming. Path values
  are percent-encoded (a `/` in a value stays inside its segment); empty, `.` and `..` path values
  are rejected.

Rationale and rejected options: [ADR-0006](../decisions/0006-tool-input-schema-shape.md).

## 3. The `instance` parameter

- **Single-instance builds:** absent.
- **Multi-instance builds:** present on **every** tool except `instances_list`; always `required`;
  `enum` of the configured instance ids (filled at startup from the runtime config). No default.
- `mixedContracts: namespaced`: no `instance` parameter; tools are duplicated per instance
  (`prod_eu__customer_list`).

## 4. Mixed contracts (`union`, default)

One tool per operation across contract versions. Properties that exist only in some versions carry
`"x-mcpgp-contracts": ["2.0"]` and "(2.0 only)" in their description. On call, input is validated
against the selected instance's contract:
- unknown property for that contract → `INPUT_VALIDATION` naming the contract;
- operation missing in that contract → `NOT_AVAILABLE_ON_INSTANCE`.

## 5. Annotations

| HTTP method | Default annotations |
|---|---|
| GET, HEAD | `readOnlyHint: true`, `idempotentHint: true` |
| PUT | `idempotentHint: true` |
| POST, PATCH | none |
| DELETE | `destructiveHint: true`, `idempotentHint: true` |

All tools: `openWorldHint: true`. Rules may override (`annotations` in a rule).

## 6. Results

Success:

```json
{
  "content": [{ "type": "text", "text": "…JSON or summary…" }],
  "structuredContent": { "…": "…" },
  "_meta": { "mcpgp/instance": "prod-eu", "mcpgp/contract": "2.0", "mcpgp/correlationId": "c-58e1d0" }
}
```

- `structuredContent` and `outputSchema` when the response schema is known (P1).
- Bodies over `responseLimitKb` are truncated with a note and a hint to narrow the query.
- Binary responses become `resource_link` content (or a base64 file object when the client can't follow links); see §13.2 for the `Accept` header.

Failure from the upstream or input validation: `isError: true` with a readable message, an error code
from [audit-and-errors.md](audit-and-errors.md) and a hint. How much of an upstream error body is
included is the operator's choice (`errors.detail`, runtime-config §4.5). No upstream call is retried
automatically; the agent decides whether to try again. Redirects (3xx) are not followed, so
credentials never travel to another address; they are returned as `UPSTREAM_4XX` with a note. Secret
values from the config are replaced with `***` in everything returned from upstream. Protocol errors (unknown tool, malformed
request) are JSON-RPC errors.

## 7. Built-in tools

| Tool | When | Purpose |
|---|---|---|
| `instances_list` | multi-instance | `{ instances: [{ name, contract, readOnly, status, tools }] }`, never credentials |
| discovery tools | `discovery` / `hybrid` | find, inspect and call tools not listed directly (§15) |
| `toolsets_list`, `toolset_enable`, `toolset_disable` | `toolsets` | switch groups of tools on and off (§15.3) |
| `search_tools`, `describe_tool` | plain + `plainSearch` | search alongside the full list |
| `describe_schema` | `packs.schema` enabled | request/response schemas on demand (§11) |
| pack tools | pack enabled (and configured) | see [packaging.md](packaging.md) §4 |

Built-in tools go through the same casing and limit steps as API tools. Prefixes:
- `instances_list`: unprefixed in single-API servers; one per API, with the API prefix, in multi-API
  servers (`orders_instances_list`, `jira_instances_list`).
- Discovery and toolset tools: never an API prefix (they span all APIs).
- Pack tools: the API prefix in single-API servers (`orders_audit_list`), none in multi-API servers.
- `naming.namespace.server`, when set, prefixes all of them.

## 8. Confirmation

`safety.confirmDestructive` (definition, **default `true`**: safe by default) makes tools with
`destructiveHint` ask before acting. Rules can also set `confirm: true` on any tool.

1. **Clients that support MCP elicitation:** the server asks the user ("Delete customer c-1 on
   prod-eu? This can't be undone.") and proceeds only on an explicit accept.
2. **Other clients:** the first call returns `CONFIRMATION_REQUIRED` with a `confirm` token and a
   summary of what will happen. The agent asks the user and calls again with the same arguments plus
   `confirm: "<token>"`.

Token rules:
- single use; expires after 5 minutes;
- bound to the tool, the instance, the exact arguments (hash) and the MCP session;
- a token for different arguments or another session is rejected (`CONFIRMATION_REQUIRED` again);
- never logged; the audit records `tool.confirm` with `asked`, `given`, `refused` or `expired`.

## 9. File inputs

Every file input uses **the same file object**, wherever it appears (a multipart part inside `body`,
or the whole `body` for `application/octet-stream`):

```json
{ "base64": "MIIDdzCCAl+gAwIBAgIE…", "filename": "partner-signing.cer", "contentType": "application/pkix-cert" }
```
```json
{ "path": "certs/partner-signing.cer" }
```

- The schema offers only the forms enabled in the runtime config (`files.base64`, `files.path`; see
  [runtime-config.md](runtime-config.md) §6): `oneOf` the enabled shapes.
- `path` is relative to the configured root; absolute paths must still be inside it.
- Base64 is decoded strictly; both forms have size caps.
- Failures return `INPUT_VALIDATION` with a reason: `FILE_FORM_DISABLED`, `FILE_OUTSIDE_ROOT`,
  `FILE_NOT_FOUND`, `FILE_TOO_LARGE`, `FILE_INVALID_BASE64`.
- Multipart example (`POST /customers/{id}/documents`):

```json
{
  "instance": "prod-eu",
  "path": { "id": "c-1" },
  "body": {
    "metadata": { "kind": "contract" },
    "file": { "base64": "JVBERi0xLjQK…", "filename": "contract.pdf", "contentType": "application/pdf" },
    "filePassword": "…"
  }
}
```

Parts that carry secrets (passwords, keys) are marked in the definition (see definition-format §5,
`parts`) and are redacted in audit and recordings.

## 10. Paging (optional)

Off by default: list tools return what the API returns, and paging uses the API's own parameters.
When a definition enables `pagination` for an API (definition-format §2.1), its list tools instead:

- take `query.limit` and `query.cursor` (the API's own paging parameters are hidden);
- return one envelope in `structuredContent`:

```json
{ "items": [ … ], "pageSize": 50, "position": 0, "hasMore": true, "nextCursor": "50" }
```

| Field | Meaning |
|---|---|
| `pageSize` | items in this page, never above the requested limit |
| `position` | 0-based position of the first item, when the API exposes it |
| `hasMore` | more items exist |
| `nextCursor` | pass as `query.cursor` for the next page; `null` when `hasMore` is false |

Style `client` slices an unpaged list response on the server so large collections never flood the
model's context.

## 11. Optional schema features

All off by default.

| Option | Effect |
|---|---|
| `schemas.input: lean` | Large request bodies are shown one level deep in `inputSchema` (property names and types); nested objects are described as "see `describe_schema`". Default `full`. |
| `packs.schema` | Adds `describe_schema(tool, part?, variant?)`: returns the full input schema of a tool, summarized one level deep with drill-down into one named definition (`part`), and narrowing of `oneOf` / discriminator variants (`variant`). Reads bundled data only; no instance, no network. |

## 12. Sensitive tools (optional)

A tool is sensitive when it moves secrets through the conversation (returns keys or tokens, takes
passwords). Marking is opt-in: a rule with `sensitive: true`.
For a sensitive tool:

- its description starts with `SENSITIVE:` and tells the agent to call it only on an explicit user request;
- its arguments and results are masked in audit and recordings;
- it is excluded by the `read-only` and `read-write` access presets and must be selected explicitly;
- the build fails if a sensitive tool lacks the warning (e.g. a rule overwrote the description).

## 13. Content types

Nothing at runtime chooses a content type from a list. Every choice is made at build time, recorded in
the manifest, and sent exactly as recorded.

### 13.1 Request content type

- Each tool is bound to **exactly one** request media type (or none, for operations without a body).
- An operation that accepts several request body types (e.g. `application/json` and
  `multipart/form-data`) is handled by the API's `requestBody` setting (definition-format §2.1):
  - `split` (default): **one tool per media type**. The JSON tool keeps the base name; the others get
    a suffix from the naming pipeline (`orders_customer_create`, `orders_customer_create_multipart`).
  - `prefer`: **one tool**, bound to the first type in the `prefer` list that the operation accepts.
- A rule may bind a specific tool explicitly: `contentType: multipart/form-data`.
- The `Content-Type` header is the bound type (with the multipart boundary when applicable). The
  runtime never falls back to JSON.

### 13.2 Accept header

- `Accept` lists the media types the operation declares for the responses the tool handles (success
  and error), in the spec's order, e.g. `application/octet-stream, application/problem+json`.
- It is **never collapsed** to a single JSON type.
- A rule may override it per tool: `accept: [application/pkix-cert]`.
- Binary success responses are returned as resource links or base64 file objects (§6, §9).

### 13.3 Body check before sending

If a call's input contains body fields or file parts, the runtime verifies that the outgoing request
has a non-empty body encoded as the bound type, with every provided part present. If not, the call is
**not sent**: the tool returns `INTERNAL` with "request body could not be encoded as <type>", and the
audit event records the mismatch. A request is never sent with an empty or wrongly encoded body.

### 13.4 Multipart part content types

One request has one `Content-Type`; a multipart request carries several content types inside it, one
per part.

- **Source of each part's type:** the OpenAPI `encoding.<part>.contentType` (3.x), then a rule's
  `parts.<name>.contentType`, then the OpenAPI defaults: objects and arrays → `application/json`,
  binary → `application/octet-stream`, scalars → `text/plain`.
- **File parts:** the file object's `contentType` (§9) is used only if it is one of the types allowed
  for that part; otherwise `INPUT_VALIDATION`. When several are allowed and none is given, the first
  allowed type is used.
- **JSON parts** (e.g. a `payload` object next to files) are serialized as JSON and sent with
  `Content-Type: application/json`, never as a plain text field.
- Each part's `Content-Disposition` carries its name and, for files, the filename.

### 13.5 Wire tests

The conformance suite contains, for every tool with a multipart body, a binary body or a binary
response, a call against the mock target that asserts what was received: `Content-Type`, `Accept`,
body size, multipart part names, each part's `Content-Type`, and filenames. They run for the runtime and for every generated
client (spec/cli.md `mcpgp test`).

## 14. Resources, prompts and progress

MCP features beyond tools, all part of the MCP specification.

### 14.1 Resources

A `GET` operation can be exposed as an MCP **resource** instead of a tool, with a rule
`expose: resource` (definition-format §5). It is then a resource **or** a tool, never both.

| Operation | Resource |
|---|---|
| `GET /status`, no parameters | resource `api://status` |
| `GET /customers/{id}` | resource template `api://customers/{id}` |
| `GET /customers{?expand}` (query parameters) | resource template `api://customers{?expand}` (RFC 6570) |

- Scheme from `resources.uriScheme` (default `api`). In multi-instance builds the instance is the
  first path segment: `api://prod-eu/customers/{id}`.
- `name` from the naming pipeline, `title` from `summary`, `description` from `description`,
  `mimeType` from the first successful response's content type.
- Query parameters not declared on the operation are dropped (and logged), never forwarded.
- Text-like media types (`text/*`, JSON, XML, YAML, anything with a `charset`) are returned as text;
  everything else as base64 blob.
- Marking a non-`GET` operation as a resource fails the build.

### 14.2 Prompts

Prompts are defined in the definition (definition-format §2.1, `prompts`), as message templates:

```yaml
prompts:
  - name: refund_review
    description: Review an order before refunding it
    arguments:
      - { name: order_id, description: Order to review, required: true }
    messages:
      - role: user
        content: "Look up order {{order_id}}, check its payment status and tell me whether a refund is allowed."
```

- Mustache placeholders; a missing required argument fails the request; a missing optional one
  renders empty.
- Prompt names go through the naming pipeline (prefix included).
- Argument completion (`completion/complete`) is offered from an `enum` when the argument declares one.

### 14.3 Progress

While an upstream call is in progress, the server sends `notifications/progress` at a fixed interval
(default 1 s, below the read timeout) when the client sent a `progressToken`. Requires a stateful
connection (stdio, or stateful Streamable HTTP); on stateless HTTP it is silently unavailable.

## 15. Exposure modes

How tools reach the agent. Large APIs overflow the model's context in `plain` mode; the other modes
load tool definitions only when needed.

| Mode | `tools/list` contains | Best for |
|---|---|---|
| `plain` | every tool | up to ~50–100 tools; also clients that do their own tool search |
| `discovery` | 3 discovery tools (+ packs, `instances_list`) | very large APIs |
| `toolsets` | toolset tools + tools of enabled toolsets | APIs with clear areas (tags) |
| `hybrid` | pinned tools + discovery tools | a few common tools, the rest on demand |

### 15.1 Discovery, `search` style (search → describe → call)

| Tool | Input | Output |
|---|---|---|
| `search_tools` | `query` (text), `limit` (default 10), `instance`* | `[{ name, title, summary, readOnly }]`: one line each, ranked |
| `describe_tool` | `name`, `instance`* | the tool's full definition: description, `inputSchema`, annotations |
| `call_tool` | `name`, `arguments`, `instance`* | the tool's normal result |

### 15.2 Discovery, `catalog` style (list → schema → invoke)

| Tool | Input | Output |
|---|---|---|
| `list_operations` | `tag`? , `query`?, `cursor`? | `[{ name, method, path, summary }]`, paged |
| `get_operation_schema` | `name` | `inputSchema` and response summary |
| `invoke_operation` | `name`, `arguments`, `instance`* | the tool's normal result |

\* in multi-instance builds.

Rules for both styles:
- Ranking: BM25 over tool name, title, tags and description; exact name matches first.
- `call_tool` / `invoke_operation` apply exactly the same validation, confirmation, sensitivity,
  audit and auth as a directly listed tool; hidden or excluded tools can't be called.
- Results stay small (one line per tool) so a search never floods the context.

### 15.3 Toolsets

- Groups from `exposure.toolsets` (default: one per tag).
- `toolsets_list` → `[{ name, description, tools: <count>, enabled }]`; `toolset_enable(name)` /
  `toolset_disable(name)` change the session's tool list and send `notifications/tools/list_changed`.
- Requires clients that refresh on `list_changed`; for others, use `discovery`.

### 15.4 Clients with their own tool search

Some clients search and load tools on demand themselves. For them `plain` works best, and the quality
of names, titles and descriptions matters most; nothing extra is needed on the server side.

## 16. Security considerations

- Tool results are untrusted data for the agent; generated agent docs say so.
- Destructive tools confirm by default (§8); sensitive tools are opt-in, warned and masked (§12).
- Discovery `call_tool` applies exactly the same checks as direct calls (§15).
- Content types are fixed at build time and bodies are checked before sending (§13).
- File inputs are validated and size-capped (§9).

See [../security.md](../security.md).
