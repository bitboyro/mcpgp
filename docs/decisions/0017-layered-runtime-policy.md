# 0016. Layered runtime policy and configuration templates

- Status: Accepted
- Date: 2026-10-21

## Context

Operation selection currently determines which tools ship in a build. OAuth scope mappings can
restrict callers, and runtime configuration can reload, but operators cannot configure a general
tool allowlist at startup or change it live. Operators need consistent restrictions across shared
organization policy, deployments, upstream instances, identities and sessions. Contract import
also needs reusable starting configurations with explicit acceptance.

## Decision

Use one policy evaluator for startup, reload, discovery and execution. Build capabilities are the
ceiling. Organization, deployment, API, instance, identity and session restrictions intersect;
denials at any applicable level win. Ordinary settings have field-specific precedence and bounds,
rather than inheriting permission merge rules.

Offer Blank, Default, Personal use and Enterprise grade as versioned configuration templates after
contract import. Preview and acceptance materialize editable configuration. Template upgrades are
explicit and cannot relax enforced parent policy.

The detailed contract and implementation stages are in
[layered-runtime-policy.md](../spec/layered-runtime-policy.md). This decision accepts the design;
the new configuration fields and APIs are not implemented yet.

## Consequences

- A running deployment can restrict shipped capabilities without rebuilding.
- Every discovery and execution path must use the same policy snapshot.
- Live updates need atomic publication, revision checks, audit and effective-policy explanations.
- Standalone deployments need no organization service; managed deployments need an explicit
  organization-policy delivery mechanism before that level can be enabled.
- Existing configurations retain their behavior when the new fields are absent.
- Implement deployment and API/instance policy first, then identity/session composition and
  organization distribution. Templates use the same policy model.

## Alternatives considered

- Build-time selection only: requires a new package for each deployment restriction.
- Independent filters for each level: makes discovery and call enforcement diverge.
- Last-writer-wins permissions: lets a child scope accidentally bypass a parent restriction.
- Templates as continuously inherited configuration: causes later template edits to change
  deployments without acceptance.
