# 0011. The platform never calls customer target servers

- Status: Accepted
- Date: 2026-10-09

## Decision

Specs come from files (upload, git, allow-listed URLs), never from live target servers. Contract
detection and connection tests run at the customer's site. While building, tools are tried against a
mock generated from the spec's examples, or with `mcpgp try` on the builder's own machine (TODO: not in 0.1.0).
Target servers shown in the platform UI are examples of what the operator will configure.

## Consequences

The platform needs no network access to customer systems; the security story stays simple.
