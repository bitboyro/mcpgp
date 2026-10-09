# 0006. Tool input schema shape: grouped by location

- Status: Accepted
- Date: 2026-10-09

## Context

An HTTP operation takes inputs from several places (path, query, headers, cookies, body); an MCP tool
takes one JSON object. The shape we choose is hard to change after release: agent prompts, skills and
test cases depend on it. **Consistency across all tools is the priority**: agents, docs and tests
should be able to rely on one structure.

## Decision

Inputs are grouped by location under fixed keys: `path`, `query`, `header`, `cookie`, `body`.
`instance` (multi-instance builds) stays at the top level because it belongs to mcpgp, not to the API.
A group appears only when the operation has inputs of that kind. Parameter names inside a group are
the OpenAPI names, unchanged. Auth inputs are never exposed. See spec/tools-contract.md §2.

```json
{ "instance": "prod-eu", "path": { "id": "o-1001" }, "query": { "notify": true }, "body": { "amount": 25 } }
```

## Consequences

- One rule, no exceptions: no name clashes, no prefixes, trivial mapping back to the HTTP request.
- One extra nesting level compared with a flat shape; mitigated by a short description on each group.
- Override rules address parameters by group when names repeat across locations
  (`params: { "path.id": … }`).
- Test cases and examples use the grouped shape.

## Alternatives considered

- **Flat, body under `body`:** shorter, but parameters with the same name in two locations need
  prefixes (`path_id`, `query_id`), so the rule has exceptions.
- **Flat when unambiguous, wrapped (`_params` / `_body`) only when both exist** (infobip-openapi-mcp):
  shortest per tool and proven in production, but the shape differs between tools depending on what
  each operation has. Rejected for consistency.
