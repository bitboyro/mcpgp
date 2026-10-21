# ADR-0016: Optional exclusion diagnostics in manifests

Status: Accepted

## Context

Excluded operations cannot be called, but recording every exclusion in packaged manifests adds
noise and size. Compiler diagnostics already explain why operations were left out.

## Decision

Omit `OPERATION_EXCLUDED` from manifests by default. Add the build flag
`--include-excluded-diagnostics` and a compiler option to retain these entries for troubleshooting.
Keep complete compiler and CLI diagnostics and all other manifest diagnostics.

## Consequences

Default manifests become smaller without changing tools or runtime behavior. Builds that need
exclusion metadata must opt in. MCP tool listings do not include manifest diagnostics.
