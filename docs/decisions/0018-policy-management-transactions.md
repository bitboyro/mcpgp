# 0017. Policy administration uses separate credentials and previewed file transactions

- Status: Accepted
- Date: 2026-10-21

## Context

The first layered-policy implementation supports startup and file reload. Remote operators now
need to inspect, preview and apply tool-policy changes without granting ordinary MCP clients
configuration-write privileges or creating a competing configuration store.

## Decision

Add an opt-in HTTP policy management API with separate named administrator keys. Reuse the original
unresolved runtime configuration file as the durable source, and use the startup loader for previews.
Bind expiring previews to an administrator and source/effective revision. Validate and prepare the
runtime before atomically persisting a candidate, then publish through the reload path. File reloads
and API transactions share a lock. General configuration writes and live auth/listener changes are
outside this first API. See [the API contract](../spec/runtime-policy-management.md).

## Consequences

- MCP credentials and scope grants cannot administer policies.
- Secret references remain references; previews return policy information rather than raw config.
- Restart recovers the accepted file and discards uncommitted previews.
- Managed callers coordinate with optimistic revisions; unmanaged file editors must avoid concurrent
  writes. The source is checked immediately before replacement, but arbitrary external writers do
  not participate in the transaction lock.
- This first writer serializes YAML and discloses comment/formatting loss in preview. A later writer
  can preserve formatting without changing policy semantics or authorization.
- Read-only mounts can be inspected/previewed; failed persistence does not publish a new snapshot.
- A successful replacement with unavailable directory sync returns a durability warning, avoiding
  a false unchanged-file error and an unsafe blind retry.

## Alternatives considered

- Reuse MCP authentication: makes possession of an execution credential imply administrator access.
- Persist resolved runtime objects: risks writing actual credentials into configuration files.
- Add an implicit override file: creates another precedence layer and complicates file/API ownership.
- Write the config before validation: lets invalid state survive a crash and break the next startup.
