Put the exported OpenAPI documents here:

- `orders-1.0.json`: Swagger 2.0 export from Orders API 1.0
- `orders-2.0.yaml`: OpenAPI 3.1 export from Orders API 2.0 (may `$ref` files under `schemas/`)

Specs are always files in the project. The platform never fetches them from a running Orders API
([ADR-0011](../../../../docs/decisions/0011-platform-never-calls-targets.md)).
