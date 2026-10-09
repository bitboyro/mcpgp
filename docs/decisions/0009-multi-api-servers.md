# 0009. A server can combine several APIs

- Status: Accepted
- Date: 2026-10-09

## Decision

A definition has `apis: [...]`. Each API has its own contracts, selection, downstream auth and (at
runtime) its own instances. Tool names are prefixed per API. The single-API case (`api:`) is sugar.

## Consequences

- Runtime config groups instances per API in multi-API servers.
- Priority: P1. The model is in place from v1 so single-API definitions don't change later.
