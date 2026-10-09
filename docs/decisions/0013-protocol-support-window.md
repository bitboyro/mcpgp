# 0013. Protocol support window and adapter layers

- Status: Accepted
- Date: 2026-10-09

## Context

MCP publishes dated revisions several times a year; OpenAPI adds major versions every few years.
Delivered packages are self-contained and live a long time at customers.

## Decision

- The manifest is the stable middle layer. OpenAPI changes are absorbed by the compiler
  (normalization into one internal model); MCP changes by a per-revision MCP adapter in each runtime.
- Each runtime release supports a window of MCP revisions: the newest plus those from the last
  18 months, at least the 3 most recent. Features are gated per negotiated revision with defined
  fallbacks; nothing is dropped silently.
- OpenAPI features have an explicit support status; unsupported constructs produce diagnostics.
- New standard versions go through a `protocol-update` issue, an ADR when capabilities change,
  conformance fixtures, and a runtime release. Builders adopt by rebuilding.

## Consequences

- Definitions rarely change because of protocol changes.
- The conformance suite must run per supported MCP revision.
- We maintain a public compatibility matrix and a deprecation policy (spec/compatibility.md).
