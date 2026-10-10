# 0014. The platform is self-hosted, single-tenant (for now)

- Status: Accepted
- Date: 2026-10-10
- Deciders: Roman Botnari

## Context

The platform (REST API, definition store, builds, web UI) has to run somewhere. A hosted offering
would mean multi-tenancy, tenant isolation, billing and an operations team; a self-hosted platform
runs inside the builder's company, next to their source control and CI.

## Decision

The platform is **self-hosted**: the builder runs it on their own infrastructure, for one
organization (one tenant). A hosted offering and multi-tenancy are reconsidered only if the product
succeeds and there is demand.

## Consequences

- No tenant model in the API or the store; one organization per installation. Data structures keep an
  organization-neutral shape so a tenant id can be added later without reshaping everything.
- Identities and sign-in fit a company setup (tokens for agents and pipelines; company sign-in for
  people).
- Installation, backup and upgrade of the platform itself become part of the product (one jar or
  image, documented storage).
- Packages stay self-contained either way (ADR-0003): the platform is never needed to run them.

## Alternatives considered

- Hosted multi-tenant SaaS from the start: much more to build and operate before the core is proven.
- Both at once: doubles the deployment and security surface.
