# 0010. Variants extend a parent and store only overrides

- Status: Accepted
- Date: 2026-10-09

## Decision

`extends: <parent>@latest|@<version>` creates a variant; the variant file contains only differences,
merged on top of the parent with the definition merge rules. "Duplicate" is a separate action that
copies the definition and its files into an independent server.

## Consequences

- Fixes to a parent flow into variants that follow `@latest`.
- Builds record the resolved parent version.
