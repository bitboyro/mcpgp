# 0004. Multi-instance: `instance` is required, no default

- Status: Accepted
- Date: 2026-10-09

## Context

Multi-instance servers talk to several target servers (e.g. prod and test). A default instance
risks writes going to the wrong system and makes tool schemas depend on runtime config.

## Decision

- Single-instance builds: tools have no `instance` parameter.
- Multi-instance builds: every tool except `instances_list` has a **required** `instance`
  parameter, an enum of configured instance ids. There is no default instance, even when only one
  instance is configured.

## Consequences

- Tool schemas depend only on the build, not on how many instances an operator configures.
- Agents must call `instances_list` or know the names; error messages list valid names.
- Switching a server from single to multi is a breaking change (new required parameter).
