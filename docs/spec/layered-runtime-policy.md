# Spec: layered runtime policy and configuration templates

Status: **accepted design, deployment/API/instance policy and policy-management API implemented** · Decision:
[0017](../decisions/0017-layered-runtime-policy.md).

This document defines the complete extension to [runtime-config.md](runtime-config.md). Deployment
and API/instance `tools.allow` / `tools.deny`, startup overrides, live reload and configured policy
inspection are implemented in the current branch (runtime-config §4.7). Administrator policy
inspection/preview/apply is implemented in [runtime-policy-management.md](runtime-policy-management.md).
Organization and identity policy files and caller session narrowing are implemented as described in
[policy-sources-and-sessions.md](policy-sources-and-sessions.md); general configuration management remains planned. Existing OAuth
scope filtering is unchanged. Read-only settings retain their existing tool-list behavior and
continue to block writes at execution.

## 1. Levels and ownership

| Level | Owner | Purpose |
|---|---|---|
| Build | Server author | Shipped tools, capabilities, immutable ceilings and defaults |
| Organization | Organization administrator | Shared defaults and enforced constraints |
| Deployment | Deployment operator | Settings and tool restrictions for a running server |
| API | Deployment operator | Restrictions shared by an API's upstream instances |
| Instance | Deployment operator | Restrictions for one upstream target |
| Identity | Access administrator | Restrictions for an authenticated user or service account |
| Session | Client, within granted access | Temporary restrictions for one connection |

Organization policy is optional for standalone servers. Identity and session are additional
permission constraints, not sources for arbitrary server settings. A session can narrow its own
permissions; it cannot edit deployment configuration or grant itself roles. Organization policy
must arrive through a trusted administrator-controlled source, never from ordinary MCP requests.

## 2. Composition

### 2.1 Permissions

For tool `t`, target instance `i`, identity `u` and session `s`, permission is the intersection of
the build capability and every applicable organization, deployment, API, instance, identity and
session selection. A denial at any level wins. Parent restrictions cannot be overridden below.

Each level may specify `tools.allow` and `tools.deny`, using exact final tool names or `*` globs
(case-sensitive; `*` matches zero or more characters; no other wildcard syntax).

- An absent `allow` adds no restriction. `allow: []` permits no API or extension tools.
- Entries within one allowlist are alternatives. All applicable allowlists must match.
- An absent or empty `deny` adds no denial. Any matching denial blocks the tool.
- Read-only constraints combine with allowlists; a name match cannot bypass read-only policy.
- Unknown exact names reject the configuration, naming the originating level and field.
- Globs resolve against the shipped manifest. Zero matches produce a preview warning and grant
  nothing. They never cause tools outside the build to appear.
- Multiple roles/scopes retain their existing within-identity grant semantics. Their resulting
  grants are still intersected with all outer policies and explicit denials. Session policy
  cannot supply additional scope grants.

The allow/deny lists select API and extension tools. Protocol control methods remain available.
Built-in discovery, instance listing, toolset activation and invocation helpers must filter their
results and enforce policy on each tool or instance they reach. A helper is never an escape hatch.
New built-ins must declare how they enforce policy before being exposed.

### 2.2 Ordinary settings

Build defaults → organization defaults → deployment → API → instance is the precedence order
only for fields supported at those levels. Unsupported placement is a validation error.

The field registry defines allowed levels, merge behavior, live-update support and bounds.
Scalars use the most specific value; lists replace rather than merge unless explicitly defined
otherwise. Permission lists always follow §2.1. Enforced organization constraints and immutable
build limits validate the resulting value; an invalid value is rejected rather than silently
clamped. Secrets retain the existing reference and redaction rules.

### 2.3 Proposed deployment document

```yaml
tools:
  allow: ["orders_*", "customers_get"]
  deny: ["orders_delete"]

apis:
  orders:
    tools:
      deny: ["orders_refund"]
    instances:
      - id: production
        baseUrl: https://api.example.com
        tools:
          allow: ["orders_list", "orders_get"]
```

Single-API builds also support `instance.tools` or `instances[].tools` alongside top-level
`tools`. Environment variables and CLI flags follow the existing configuration naming and
precedence conventions. Source precedence selects a level's document first; hierarchy evaluation
then combines the levels. CLI flags cannot override organization constraints.

The tool-policy fields above are supported by the generic and generated configuration schemas.
Organization, identity and session documents are delivered separately with their own ownership
and validation; they are not client-controlled additions to this deployment document.

## 3. Discovery and execution

One immutable effective-policy snapshot serves plain tools, discovery, toolsets, resources derived
from operations, and calls over both HTTP and stdio. Filter catalogs, search results, descriptions,
instance lists and activation results so disallowed capabilities are not leaked through helpers.

Advertise an instance-backed tool only when at least one instance accessible to the caller permits
it. Filter its advertised instance enum accordingly. A call must recheck the selected instance,
identity and session even if the tool was previously listed. Reject a disallowed call before any
upstream request or extension side effect, including calls made by helpers. Extensions must
declare their API/instance binding when applicable; unbound extension tools still obey the outer
levels. Toolset activation never grants permission.

Changing permissions updates affected catalogs and sends `notifications/tools/list_changed` to
connected clients that support it. Notifications are advisory: enforcement never depends on a
client refreshing its list. Pagination cursors must be bound to caller context and policy revision;
a stale cursor requires a fresh listing rather than returning a cached page from an old policy.

## 4. Startup and runtime updates

Use the same parser, validator and evaluator at startup and reload. Compile a complete candidate
snapshot before publishing it. Invalid configuration leaves the last valid snapshot running;
invalid initial configuration fails startup.

| Intended live changes | Restart required initially |
|---|---|
| Tool allow/deny, read-only restrictions, supported timeouts and rate/concurrency limits, supported logging fields | Transport, listener address/port/TLS setup, inbound authentication configuration, storage backend/root |

The field registry is authoritative; adding a field does not make it reloadable automatically.
Existing credential and upstream certificate reload support remains intact. Stage 1 does not
promise live reload of OAuth scope mappings because inbound auth currently requires restart.

A management update follows these steps:

1. Authenticate and authorize the administrator for the level being changed.
2. Read the expected revision; reject stale writes without replacing newer settings.
3. Preview changes, effective restrictions, warnings and restart-required fields without mutation.
4. On explicit apply, revalidate against the current parent policy and revision.
5. Persist the desired revision durably and publish the complete live snapshot atomically with
   respect to request admission. Failed persistence must not publish a new active revision.
6. Audit actor, source, old/new revisions, redacted changes and outcome; notify affected sessions.

Expose desired and active revisions separately when restart-required changes exist. A preview
does not reserve a revision. Applying a stale preview requires another preview. File reloads use
the same validation and publication path; invalid files do not replace active policy. A managed
deployment declares its authoritative configuration source so a file watcher and management API
cannot silently overwrite one another. File-backed writes use atomic replacement and optimistic
revision checks. Crash recovery loads the last durably accepted desired configuration.

Calls admitted before publication finish under their captured snapshot. New calls use the new
snapshot, including calls from existing sessions. Session restrictions disappear on session end;
session recreation must not restore permissions revoked at a parent level.

Management endpoints are separate from ordinary MCP tool execution and require explicit admin
authorization. The first policy-only routes, request schemas and errors are defined in
[runtime-policy-management.md](runtime-policy-management.md); general-setting writes remain planned.
Organization policy distribution must preserve the last accepted restrictions during outages;
missing required policy at initial startup fails closed.

## 5. Effective configuration and audit

Provide an administrator view containing desired/active revisions, pending restart fields, each
effective setting's source, and a tool decision explanation such as “orders_delete denied by
organization policy.” Explain all contributing restrictions without returning secrets. Ordinary
callers receive only information authorized for them, not organization policy or other identities.

Audit successful and rejected updates, including origin (file, CLI-triggered reload or management
API), actor when available, correlation id and revisions. Preserve existing secret redaction and
record policy revision on call audit events so decisions can be traced after later updates.

## 6. Templates after contract import

Templates are reusable, versioned Library items. Import flows offer four built-ins once the
contract loads:

| Template | Initial intent |
|---|---|
| Blank | Contract loaded; no API tools enabled; configure manually |
| Default | Current generated behavior: one tool per selected operation |
| Personal use | Local stdio configuration; read operations enabled initially; writes opt-in |
| Enterprise grade | Explicit tool selection, authentication setup, audit logging and stricter limits |

Flow: load contract → choose template → preview → accept or skip → customize. Do not apply a
template merely by selecting its card. Skip retains the existing/generated configuration.
Replacing or adding a contract on an existing server preserves configuration unless the operator
explicitly applies a template and accepts the diff.

Templates contain supported definition and deployment defaults, required-input declarations and
an immutable id/version. They cannot contain resolved credentials or grant organization/identity
authority. Enterprise grade requires explicit tool selection and authentication setup before it is
ready to start; it does not invent credentials or certify compliance. Preview shows unresolved
inputs and parent-policy conflicts. No operation is assumed read-only solely from its HTTP method
when stronger contract annotations or existing rules say otherwise.

Acceptance materializes editable configuration and records template id/version as provenance.
Template changes never silently alter existing deployments. An upgrade is a previewed migration
against the current edited configuration, preserving overrides or surfacing conflicts explicitly.
Blank must use runtime `allow: []` while preserving shipped capabilities if later live enabling is
desired; excluding tools from the build would require a rebuild to enable them.

## 7. Delivery and acceptance

1. **Deployment and API/instance policy:** schemas, generated schemas, runtime model, shared
   evaluator, config/env/CLI startup, reload, discovery/call enforcement and effective-policy view.
2. **Administration:** preview/apply management contract, authorization, revision checks, desired
   versus active state, persistence and audit integration.
3. **Identity and session:** compose existing OAuth grants with outer restrictions; add authorized
   session narrowing and session-specific catalogs. Preserve static/anonymous identity semantics.
4. **Organization:** trusted distribution, administrator ownership, enforced constraints and outage
   behavior. No claim of organization enforcement before this delivery path exists.
5. **Templates:** Library format/versioning, import preview/accept/skip, four built-ins and upgrade
   conflict handling, using the same policy validator.

Each stage updates its public specs, schemas, feature matrix and release notes. Test permission
intersection, empty versus missing allow, denial precedence, invalid names, instance selection,
scope composition, helper bypass attempts, HTTP/stdio parity, stale pagination and revisions,
concurrent reload/calls, persistence failures and secret redaction. Verify invalid updates leave
active behavior intact and that no blocked call reaches the upstream. Template acceptance tests
cover skip, Blank, required inputs, parent constraints and upgrades over user edits.
