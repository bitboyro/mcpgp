# 0008. Discovery is an exposure mode, not a pack

- Status: Accepted
- Date: 2026-10-09

## Decision

`search_tools`, `describe_tool` and `call_tool` are controlled only by `exposure.mode`
(`plain` / `discovery` / `hybrid`) and `exposure.plainSearch`. Support packs are only for optional
tools that don't come from contracts.

## Consequences

One control per decision in the UI; internally discovery may reuse the pack machinery.
