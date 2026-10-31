# 0.5.0: solidify, expose and consolidate the core

Direction agreed 2026-10-31. Work proceeds in reviewable stages with discussion between them.
This records scope, not completed implementation or a release date.

1. Review existing capabilities and decide their configuration and documentation surfaces.
   Start with the [documentation and capability review](documentation-review.md).
2. Consolidate documentation around getting started, building servers, running generated servers,
   administering the platform, and reference. Separate contributor material. Maintain repository
   sources for publication on a public product website and linking from platform help.
   The public website explains the product and hosts documentation, with a possible blog;
   operational administration belongs in the platform.
3. Assess generated-server security, including artifact-specific SBOMs, dependency scanning,
   provenance and runtime protections. Establish existing coverage before deciding fixes.
   Repository scans alone do not demonstrate coverage of each generated deliverable.
4. Publish a living European API catalogue in its own GitHub repository: individual servers
   and profession-focused multi-API bundles authored through MCP GP definitions and API
   specifications. Build/release monthly and scan published artifacts weekly.
   The [catalogue plan](european-api-catalogue-plan.md) and
   [source inventory](european-api-inventory.md) record scope, evidence and proposed stages.
5. Finish M10 G3–G5 after the inventory establishes the coverage baseline: broader behavior and
   operator checks, negative tests, coverage accounting and performance budgets against the
   previous release. G1/G2 are in place; see the [release-gate plan](release-gate.md).

Delivered package documentation and agent guidance are included in consolidation. Each stage
must distinguish existing support, verified behavior, known limitations and planned work.

## Deferred to 0.6.0 planning

- Standalone platform distribution without Docker (0.4.0 R09).
- Complete versioned-template selection/application workflow in the web UI (0.4.0 R07).
- Other feature expansion, including protocol additions, code generation, fleet management,
  expanded templates and advanced UI workflows.

These are deferred candidates, not a commitment to ship the entire backlog in 0.6.0.
