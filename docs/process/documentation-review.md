# Documentation and capability review

Status: initial structure agreed 2026-10-31; capability-by-capability verification is pending.
Part of [0.5.0 consolidation](release-0.5.0.md).

## Audiences and navigation

The [documentation entry point](../README.md) uses six sections: Get started, Build a server,
Run a server, Run the platform, Reference, and Contribute. Builders author the definition;
server operators configure delivered servers; platform administrators operate the authoring service.

Keep architecture, decisions, development process and release-gate internals behind the contributor
entry point. Preserve existing paths during this first pass, including links used by delivered
packages. Physical moves require checking incoming links and package assembly references.

Repository Markdown is the shared documentation source. The public website will publish it with
the same task navigation. Platform controls should link to the relevant task or reference section
and include short help explaining the consequence of the setting. Website framework, domain,
versioned URLs and offline help behavior remain implementation decisions to discuss.

Guides explain a concrete outcome, prerequisites, steps, verification and common failures.
Reference pages describe exact settings and defaults. Avoid copying configuration contracts into
multiple independently maintained guides. Mark unsupported features and operator-only settings
explicitly. Public capability claims must match released behavior.

## Initial findings

| Finding | Evidence | Action |
|---|---|---|
| User tasks and contributor material shared a flat index | Previous docs/README.md | Introduce task navigation and a contributor entry point (this pass). |
| Many user tasks have only detailed specifications | Definition, runtime config, authentication and packaging references | Write short task guides backed by runnable examples. |
| The feature matrix excludes the platform | Scope statement in feature-matrix.md | Inventory platform UI, REST API, MCP tools, library, builds, identities and approvals as well. |
| The inventory predates current release changes | Matrix says revalidated at 20a8ca1, 2026-10-23 | Recheck entries against an identified revision. |
| Old discovery findings are not recorded in the release plan | release-0.4.0.md references C02 and A01–A16 without their contents | Recover evidence if available; otherwise reconstruct findings from source with new IDs. |
| Release allocation is stale | Roadmap assigned standalone distribution and template UI to 0.5.0 | Record consolidation scope and 0.6.0 deferrals. |

## Review record for every capability

Use stable existing F identifiers for generated-server/compiler capabilities. Assign separate P
identifiers to platform capabilities. Do not infer support from a specification or a test name alone.

Each record must contain:

- User task, audience and capability ID.
- Current support: implemented, partial or unsupported; source revision and evidence.
- Current configuration paths: definition, CLI, platform form, REST API, platform MCP tool,
  runtime file/environment, or runtime administrator interface; record gaps explicitly.
- Exposure decision: retain/improve a dedicated form, document a generic authoring path,
  explain operator configuration, or clearly mark unsupported/deferred behavior.
- Task guide and precise reference destination; proposed platform help location.
- Verification evidence and missing release-gate coverage, linked to existing feature IDs.
- Decision status: proposed, agreed or implemented, with follow-up work visible.

Distinguish build-time defaults from deployment configuration, client sign-in from target API
credentials, platform activity from runtime audit, and platform administration from runtime
operation. A working API does not establish that the corresponding UI or MCP tool exists.

## Review sequence

1. Trace one first-server journey: specification → definition → selected tools → build → connect.
   Map the existing CLI, UI and agent paths, and identify the first missing guide.
2. Review authentication, tool access, runtime policy and deployment responsibilities.
3. Review API versions, naming, instructions, execution, results and observability.
4. Review packaging, updates, extensions, platform administration and delivered agent docs.
5. Reconcile all feature-matrix entries and platform capabilities, then use the gaps to scope
   generated-artifact security work, the living example and G3–G5 verification.

This pass supplies navigation and the review method. It does not mark the inventory review,
website, contextual help, security assessment or release gate as complete.
