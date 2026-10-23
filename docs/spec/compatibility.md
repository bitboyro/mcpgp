# Spec: protocol and standards compatibility

Status: **draft v1** · Owner: core + runtime · Decision: [ADR-0013](../decisions/0013-protocol-support-window.md)

mcpgp sits between two moving standards: **OpenAPI** on the input side and **MCP** on the output side.
Our own formats (definition, manifest, runtime config, audit events) sit in between and change on
their own schedule. This document says how each kind of change is absorbed.

```
OpenAPI 2.0 / 3.0 / 3.1 / 3.2 …  ──►  compiler  ──►  manifest (ours, versioned)  ──►  MCP adapter  ──►  MCP revisions
        input side                  normalizes        stable middle layer           per revision        output side
```

The manifest is the shock absorber: OpenAPI changes stop at the compiler, MCP changes stop at the
MCP adapter. Definitions and manifests don't change when either standard does, unless we want to
expose a new capability.

## 1. MCP changes (output side)

MCP publishes dated specification revisions (e.g. `2025-06-18`, `2025-11-25`). Clients and servers
agree on one revision during `initialize`.

### 1.1 Support window

- Each runtime release supports a **window** of MCP revisions: the latest revision plus the
  previous ones released in the last **18 months**, at least the **3 most recent**.
- The server negotiates the newest revision both sides support. If none overlap, it fails the
  `initialize` request with the list of supported revisions.
- The window is published in the compatibility matrix (§4) and recorded in each build's
  `manifest.json` (`runtime.mcpRevisions`) and generated `OPERATIONS.md`. `<server> doctor` prints it.

### 1.2 Feature gating per revision

The MCP adapter maps manifest features to what the negotiated revision supports, with fallbacks:

| Manifest feature | Needs revision | Fallback on older revisions |
|---|---|---|
| `outputSchema` / `structuredContent` | 2025-06-18+ | text content only (JSON in `text`) |
| Elicitation (confirm destructive, missing params) | 2025-06-18+ | `CONFIRMATION_REQUIRED` + `confirm` token |
| Tool `title`, `icons` | 2025-06-18+ / 2025-11-25+ | name only |
| Resource links in results | 2025-06-18+ | embedded text / URL in text |
| Tasks (long-running) | 2025-11-25+ (experimental) | progress notifications, or synchronous call |
| URL elicitation (per-user sign-in) | 2025-11-25+ | feature unavailable; tool explains |
| Streamable HTTP | 2025-03-26+ | legacy HTTP+SSE transport, only if the build enables `transports.legacySse` |

What the runtime does today, by protocol feature (the Java SDK it builds on stops at 2025-11-25; 2026-07-28 is
planned, see [the roadmap](https://github.com/bitboyro/mcpgp/blob/20a8ca16bec3577bce49dd57152143bfcb4c69ae/docs/process/roadmap.md)):

| Protocol feature | Support | Notes |
|---|---|---|
| Tools, resources, resource templates, prompts | ✓ | |
| Completion (`completion/complete`) | ✓ prompts, resource templates | enum and boolean values; tool arguments have no completion in MCP |
| Progress (`notifications/progress`) | ✓ | only when the client sent a `progressToken`; increasing, `total` is the instance's timeout |
| Cancellation (`notifications/cancelled`) | ✓ | a waiting API call or confirmation stops; audited `cancelled`. Over stdio the cancelled request gets no answer (as the spec says); over HTTP the SDK still writes an error result, which clients ignore |
| Pagination (`cursor` / `nextCursor`) | ✓ when `server.pageSize` > 0 | tools, resources, templates, prompts; cursors bound to the list they were made for |
| Logging (`logging/setLevel`, `notifications/message`) | ✓ | upstream failures are sent as `warning` from logger `mcpgp` |
| Elicitation, form mode | ✓ | confirmations of destructive tools; else `CONFIRMATION_REQUIRED` + token |
| Elicitation, URL mode | not yet | roadmap |
| Tasks | not yet | roadmap |
| MRTR, request-scoped stateless transport (2026-07-28) | not yet | roadmap; needs more than the SDK offers |

Protocol-level behaviour (cancellation, paging, progress, log messages) is tested in the runtime's own
tests (`CancellationTest`, `StdioCancellationTest`, `PaginationTest`, `StdioPaginationTest`): the
conformance traffic format describes one tool call and its API request, which has no step for them.

Rule: a feature is **never silently dropped**. Either a fallback applies, or the tool returns a clear
error naming the client's revision. Fallbacks are covered by the conformance suite per revision.

### 1.3 When a new MCP revision appears

1. **Watch:** a maintainer tracks the MCP specification releases and accepted proposals; a dependency
   bot tracks the MCP SDKs we use.
2. **Assess:** open a `protocol-update` issue listing every change and its impact: wire format,
   new capability, removal, security.
3. **Decide:** ADR if we adopt a new capability or drop a revision.
4. **Implement** in the MCP adapter only; add conformance fixtures for the new revision.
5. **Release** a runtime version. The window moves; the oldest revision may leave it (with notice, §5).

### 1.4 Effect on builders and customers

- New MCP support ships in a **new runtime version**. Builders get it by **rebuilding**: the
  definition doesn't change.
- Delivered packages are self-contained and keep working with clients that still speak their revisions.
  Clients generally support older revisions for a long time.
- A builder can pin the runtime version in the definition (`generation.runtime: 1.8.x`) to avoid
  surprises, and see "newer runtime available: adds MCP 2026-xx" on the platform.

## 2. OpenAPI changes (input side)

### 2.1 Accepted versions

Swagger 2.0, OpenAPI 3.0.x, 3.1.x now; 3.2 planned. Every version is normalized into **one internal
operation model** in the compiler. Overlay 1.0 is supported; Arazzo is considered later.

### 2.2 Feature support matrix

Every OpenAPI feature has an explicit status, published in this document and enforced by the
compiler:

| Feature | Status | Compiler behaviour |
|---|---|---|
| Paths, operations, parameters (all `in` + `style`/`explode`), JSON bodies | supported | — |
| `allOf`, `oneOf`, `anyOf`, discriminator | supported (flattened where possible) | warning when simplification changes meaning |
| Several request body types per operation | supported | split into one tool per type, or `prefer` (tools-contract §13) |
| Form and multipart bodies | P0 for multipart, P1 for form | warning: operation skipped until supported |
| `callbacks`, `webhooks` | not supported | info: ignored |
| `links` | not supported | info: ignored |
| 3.2 `QUERY` method, `additionalOperations`, streaming media types | planned | error until supported, naming the feature |
| Unknown `x-` extensions | passed through to provenance | — |
| `x-mcp-*` extensions | not used (rules in the definition do this) | passed through like other `x-` fields |

Rule: **no silent drops**. Anything not supported produces a diagnostic with the operation and the
spec location; the build report lists them.

### 2.3 When a new OpenAPI version appears

1. `protocol-update` issue: list new constructs and map each to: ignore, warn, support.
2. Extend the parser/normalizer and the internal model **additively**.
3. Bump the manifest version only if a new concept must reach runtimes (e.g. a new HTTP method).
4. Add fixtures to the conformance corpus: hand-written minimal specs plus public real-world specs.
5. Existing definitions keep building unchanged.

### 2.4 Not to be confused with API changes

When the upstream **API** releases a new version (a new contract, not a new OpenAPI version), that
is handled by the definition: add a contract, review the diff, the lock file keeps names stable, the
build report lists what changed. See [definition-format.md](definition-format.md) §3 and
[naming.md](naming.md) §7.

## 3. Our own formats

| Format | Version field | Change rules |
|---|---|---|
| Definition | `apiVersion: mcpgp/vN` | breaking only with a new N + automatic `mcpgp migrate` (TODO: comes with the first v2) |
| Manifest | `manifestVersion` | additive within a major; runtimes refuse unknown majors with a clear message |
| Runtime config | generated schema `$id` includes runtime version | additive; renamed keys keep an alias for one major |
| Audit event | `v` | additive; consumers must ignore unknown fields |
| Error codes | — | codes are never reused or renamed; new codes may be added |

## 4. Compatibility matrix (maintained per release)

| mcpgp runtime | MCP revisions | OpenAPI accepted | Manifest | Definition |
|---|---|---|---|---|
| 0.x (planned) | 2025-03-26, 2025-06-18, 2025-11-25 | 2.0, 3.0, 3.1 | 1 | mcpgp/v1 |

## 5. Deprecation policy

- A revision or format leaving support is announced in release notes **at least one minor release
  and 3 months** before removal.
- The platform warns builders whose latest build uses a runtime that will lose a revision their
  customers might need.
- Removals are listed first in release notes, with the replacement.
