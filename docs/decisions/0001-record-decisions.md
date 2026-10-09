# 0001. Record decisions as ADRs

- Status: Accepted
- Date: 2026-10-09

## Context

The design was worked out in long conversations and UI mockups. Reasons get lost; later
contributors (people and agents) need to know why things are the way they are.

## Decision

Every decision that affects a spec, a public contract or the architecture gets an ADR in
`docs/decisions`, using `template.md`. Accepted ADRs are immutable except for their status.

## Consequences

- A PR that changes a decided behaviour must include a new ADR that supersedes the old one.
- The ADR index in `README.md` stays current.
