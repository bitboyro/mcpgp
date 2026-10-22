# Architecture decision records

One file per decision: `NNNN-short-title.md`, using [template.md](template.md).
Statuses: **Proposed** → **Accepted** → (**Superseded by NNNN** | **Deprecated**).
Accepted ADRs are not edited except to change status; write a new ADR to change a decision.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-decisions.md) | Record decisions as ADRs | Accepted |
| [0002](0002-language-neutral-manifest.md) | One compiler, language-neutral manifest | Accepted |
| [0003](0003-self-contained-delivery.md) | Delivered packages are self-contained | Accepted |
| [0004](0004-instance-required-parameter.md) | Multi-instance: `instance` is required, no default | Accepted |
| [0005](0005-downstream-auth-per-instance.md) | Downstream auth chosen per instance; no token passthrough | Accepted |
| [0006](0006-tool-input-schema-shape.md) | Tool input schema shape: grouped by location | Accepted |
| [0007](0007-telemetry-otlp-only.md) | Telemetry via OTLP only; destinations outside the server | Accepted |
| [0008](0008-discovery-is-exposure.md) | Discovery is an exposure mode, not a pack | Accepted |
| [0009](0009-multi-api-servers.md) | A server can combine several APIs | Accepted |
| [0010](0010-variants-extend.md) | Variants extend a parent and store only overrides | Accepted |
| [0011](0011-platform-never-calls-targets.md) | The platform never calls customer target servers | Accepted |
| [0012](0012-naming-pipeline-and-lock.md) | Fixed naming pipeline with a lock file | Accepted |
| [0013](0013-protocol-support-window.md) | Protocol support window and adapter layers | Accepted |
| [0014](0014-platform-self-hosted.md) | The platform is self-hosted, single-tenant (for now) | Accepted |
| [0015](0015-postgres-store.md) | A PostgreSQL store next to the file store | Accepted |
| [0016](0016-optional-exclusion-diagnostics.md) | Optional exclusion diagnostics in manifests | Accepted |
