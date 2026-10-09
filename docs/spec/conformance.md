# Spec: conformance suite

Status: **draft v1** · Owner: core + runtime

The conformance suite is how we prove that the compiler and every runtime (Java now, others later)
behave exactly as the specs say. It is data, not code: any runtime in any language runs the same
cases.

## 1. Layout

```
conformance/
├─ cases/
│  └─ <area>/<case-name>/
│     ├─ case.yaml                 what the case covers, which specs, which MCP revisions
│     ├─ definition/               a small definition project (root .mcpgp.yaml, contracts, rules)
│     ├─ expected/
│     │  ├─ manifest.json          the manifest the compiler must produce (or)
│     │  └─ diagnostics.json       the errors/warnings it must report
│     ├─ runtime.yaml              runtime config used for the traffic part (instances point to `mock`)
│     ├─ mock.yaml                 how the mock target answers
│     └─ traffic.yaml              steps: MCP request → expected upstream request → expected MCP response
└─ runner/                         one small runner per runtime
```

Areas: `resolve` (multi-file), `naming`, `rules`, `schemas`, `content-types`, `files`, `auth`,
`instances`, `exposure`, `errors`, `security`, `compat` (per MCP revision).

## 2. `traffic.yaml`

```yaml
mcpRevision: "2025-11-25"
steps:
  - name: create with document is sent as multipart
    mcp:
      method: tools/call
      params:
        name: orders_customer_create_multipart
        arguments:
          instance: mock
          body:
            payload: { name: "Ana", accountId: "a-1" }
            document: { base64: "JVBERi0xLjQK", filename: "c.pdf", contentType: "application/pdf" }
    upstream:                       # what the mock must receive
      method: POST
      path: /api/v2/customers
      headers:
        Content-Type: { startsWith: "multipart/form-data; boundary=" }
        Accept: "application/json, application/problem+json"
      parts:
        - { name: payload, contentType: application/json, json: { name: "Ana", accountId: "a-1" } }
        - { name: document, contentType: application/pdf, filename: c.pdf, sizeBytes: 9 }
    respond: { status: 201, json: { id: "c-1", name: "Ana" } }
    expect:                         # what the MCP client must receive
      isError: false
      structuredContent: { id: "c-1", name: "Ana" }
      meta: { "mcpgp/instance": mock, "mcpgp/contract": "2.0" }
```

Matchers: exact values, `{ startsWith }`, `{ matches: <regex> }`, `{ absent: true }`, `{ any: true }`.
A step may expect **no** upstream request (`upstream: none`), e.g. for validation or confirmation.

## 3. Rules

- Every spec change that changes behaviour adds or updates cases in the same PR.
- Every error code, every content-type rule, every auth type and every exposure mode has at least one
  case.
- Cases never contain real secrets or real hosts; the mock target is always `mock`.
- A runtime is **conformant** for a release when it passes all cases for the MCP revisions it claims.
  The pass list is published with the compatibility matrix.
