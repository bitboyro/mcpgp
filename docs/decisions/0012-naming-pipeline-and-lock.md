# 0012. Fixed naming pipeline with a lock file

- Status: Accepted
- Date: 2026-10-09

## Decision

Tool names are produced by a fixed pipeline (source → normalize → shape → vocabulary → rules →
explicit → namespace → limits → collisions → lock), see spec/naming.md. Released names are kept in
`mcp-names.lock`; renames are explicit and flagged as breaking.

## Consequences

Deterministic, explainable names; spec updates don't silently rename tools.
