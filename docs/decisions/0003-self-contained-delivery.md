# 0003. Delivered packages are self-contained

- Status: Accepted
- Date: 2026-10-09

## Context

Builders deliver servers to customers whose systems (e.g. ERP systems or B2B gateways) are usually not on the internet.
Customers never get access to the platform. Security reviews block software that phones home.

## Decision

A package runs on the customer's infrastructure, connected only to the customer's own systems
(the target servers they configure) and their own MCP clients. It contains everything needed to
install, configure, verify and run the server: binaries,
config schema, example config, docs, operator commands. Nothing contacts the platform, the vendor or
the internet on its own. Licence or update checks, if ever added, are opt-in and off by default.

## Consequences

- Docs, `init`, `doctor` and `client add` ship inside the package.
- The platform cannot show runtime data from customers; its Activity covers definitions and builds only.
