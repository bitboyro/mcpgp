# 0.4.0 release scope and checkpoints

Agreed 2026-10-23; amended 2026-10-25 (navigation regroup, Access control, Observability and the
`upstreamAuth` rename brought into scope: R13–R16). Status: **planned; not a release approval or an implementation-complete claim**.
This is the release-specific scope for [requirements](../requirements.md) and the
[roadmap](roadmap.md). The broader P0/P1/P2 requirements remain unchanged.

**Release boundary:** existing capabilities work, agents can discover and use them, people can
understand the main flow, and container and noncontainer installation paths are usable.
New automation, other broad redesigns and learning infrastructure follow on the roadmap.

## Included capabilities

The release includes the changes since 0.3.0: batching, retained results, durable batch receipts
and their expiry/storage limits, layered runtime policy and management, versioned configuration
templates, large-API/import improvements, packaged documentation, CLI/runtime fixes, and the
independent packaged-server release gate improvements. The final release notes must describe the
merged implementation, its defaults, limits and compatibility changes. This scope document does
not replace the changelog.

## Required before release

Unchecked means work or acceptance evidence is outstanding. Implementation on an unmerged branch
is not completion on the release candidate.

| Checkpoint | Question / decision | Acceptance |
|---|---|---|
| [ ] R01 · Confirmed defects and misalignments | Do accepted configurations work and do exposed contracts match implementation? | Organization defaults and all built-in templates validate; API/MCP template/import/diff gaps addressed; previews and generated schemas/helpers reconciled; focused regressions pass. Integrate and review the separate defect branch. |
| [ ] R02 · Feature discoverability | Where can each supported feature be configured? | Inventory identifies dedicated UI, generic source/API/MCP and operator-only paths; unsupported values are clearly refused/documented. Intentional omissions are explained rather than treating every surface as a fleet manager. |
| [ ] R03 · Readable visual polish | Can users read forms/tables and recognize primary actions? | Larger readable base typography; softer borders and secondary surfaces; clear primary actions and focus/error states; polished labelled API/agent utilities. Check narrow screens and zoom. Exact tokens remain a design decision. |
| [ ] R04 · Plain-language copy | Does each action say what it does and what happens next? | Clarify confusing labels and API definition/contract terminology; explain checks, previews and builds accurately; consistent language in UI, errors and docs. |
| [ ] R05 · Focused Overview cleanup | What should a new or returning user see first? | Concise status, setup checklist and next useful action; detailed lists belong on relevant pages; previews remain available before activation. Use a dashboard/checklist without introducing a mandatory wizard. |
| [ ] R06 · Baseline instructions editor | Where do users set the instructions advertised to connecting agents? | Expose existing metadata.description → server.instructions behavior in a dedicated control and agent-facing preview. Explain that clients choose how to use advertised instructions. Placement: the General page (R13), which shows the instructions read-only today. |
| [ ] R07 · Complete existing template workflow | Can users select and apply existing versioned templates? | Read available built-in/Library templates instead of hardcoding choices; explicit preview/accept; review versioned application to an existing server. Correct existing presets without expanding the catalogue. |
| [ ] R08 · Release/build metadata | Which artifact is running? | Permanently visible release version and actual artifact build date; do not substitute process startup time. Placement remains a design decision. |
| [ ] R09 · Standalone platform distribution | Can a user install the platform without Docker or a source build? | Publish platform executable jar/zip with launch/config instructions, supported Java/OS requirements, upgrade/storage guidance and a container-free distribution smoke check. CLI and generated-server noncontainer paths remain usable. |
| [ ] R10 · Builds and Activity clarity | Is build history distinguished from audit history? | Clear labels/ownership: Builds contains compilation/packages/releases; Activity records who changed what, retaining linked build/release audit events. Builds is labelled Builds and releases under Deliver; Activity sits under History (R13). |
| [ ] R11 · Delivered docs and artifact truthfulness | Does the extracted distribution contain what its docs promise? | Resolve packaged links, correct unsupported SKILL.md claims, add capability-specific agent guidance, reconcile stale inventories/statuses and prepare complete release notes. |
| [ ] R13 · Server navigation regroup | Do pages group settings the way users and the standards (MCP, OpenAPI, OAuth, OpenTelemetry, API gateway consoles) name them? | Sidebar: Overview / Configure (General, API definitions, Tools, Target APIs, Access control, Execution, Observability) / Deliver (Packaging, Builds and releases) / History (Changes, Activity), as in [UI design](../design/ui.md#server-navigation-2026-10-25). Existing controls moved without loss; old addresses redirect; labels, docs and save messages use the new names. Regroup of existing settings implemented 2026-10-25, not yet on a candidate. |
| [ ] R14 · Access control page | Can people see and set who may connect to a server and what they may do? | Narrowed 2026-10-25 to what the runtime enforces: editable client sign-in methods offered and default, default scope → tool permissions, sessions default; deployment tool restrictions, OAuth/static-key details, allowed origins, the policy management API, and the administrator-mounted identity and organization policy files shown read-only as "Set when deployed". Session narrowing is shown with its on/off default. The runtime implements identity/organization policy and session narrowing (#93, policy-sources-and-sessions.md); behavioural tests added 2026-10-25 (`CallerPolicyTest`, `ReloadTest`), which found and fixed a reload defect (later reloads didn't reach unchanged tools). Behaviour verified, not only the form. |
| [ ] R15 · Observability page | Can people see and set what the delivered server records? | A page for audit events and retention, logging, traces and metrics (OpenTelemetry) and health checks, matching the supported paths, with the same "Set when deployed" marking. Distinct from platform Activity. |
| [ ] R16 · `downstreamAuth` → `upstreamAuth` rename | Does the definition use the industry term for credentials toward the target API? | Schemas, core, runtime, packager, platform, OpenAPI contract, UI, docs and examples use `upstreamAuth`. 0.3.0 definitions with `downstreamAuth` still compile as a deprecated alias with a warning; the changelog documents the migration. Remove the alias no earlier than the next minor release. The tool error code `AUTH_DOWNSTREAM` becomes `AUTH_UPSTREAM` without an alias, documented as breaking for clients that match on it. |
| [ ] R12 · Candidate rehearsal | What proves this exact candidate is ready? | Record SHA; run required CI and independent release gate; inspect extracted packages/docs; execute complete agent authoring/delivery workflow and container-free installation; review skips and accepted limitations explicitly. |

## Deferred beyond 0.4.0

No target release is assigned to these items. Preserve existing functioning controls while the
replacement design is discussed.

| Roadmap item | Question to settle later | Boundary for 0.4.0 |
|---|---|---|
| Comprehensive advanced-setting forms | Which source/API settings warrant dedicated forms? | Every feature has a supported documented path; selected instructions/template controls and the Access control/Observability pages (R14/R15) are in scope, not form parity for every property. |
| AI description enhancement and downloadable optimization skill | Skill, internal agent or both; which provider, credentials, cost, data access and review flow? | Existing manual description overrides remain; no new optimization workflow promised. |
| Tutorial/FAQ center and videos | What belongs in-product; remote streaming versus offline media? | Ship accurate written getting-started guidance; defer a new learning center and video infrastructure. |
| Expanded policy/template catalogue | What guarantees do additional presets make, and how are they versioned? | Correct and explain existing four built-ins; no large new catalogue or unsupported enterprise claims. |
| Tab-to-accept predictions | Which suggestions can be accepted without breaking ordinary keyboard navigation? | Preserve normal Tab focus behavior until the interaction is designed and tested. |
| Central runtime/fleet management | How are deployed runtimes discovered, authenticated, upgraded and administered? | Clarify platform authoring versus operator administration; no new remote fleet-control service. |
| Complete brand identity | What logo, palette and identity system represents mcpgp? | A simple reviewed logo may fit if ready, but branding is not a release blocker; full identity system deferred. |

## Delivery tracks and ownership

- **Defect track:** `codex/release-defects`, isolated worktree. Scope covers verified defects,
  contract/preview/documentation alignment and focused regression tests. It excludes UI redesigns,
  new template controls, standalone distribution and new product workflows. Review and integrate
  its commits before checking candidate acceptance.
- **UI/UX track:** agree detailed page behavior, text style, grouping and visual choices, then
  implement R03–R08, R10 and R13–R15 within the boundaries above. Release-scope agreement does not mean exact
  typography, menu labels or control placement have been approved.
- **Distribution track:** R09 is additional work, not assigned to the defect track.
- **Definition rename:** R16 spans schemas, compiler, runtime, packager and platform; it is not part
  of the defect track and lands before R14 so new pages use the new name.
- **Release review:** R02/R11/R12 span the tracks. Preserve the session inventory findings in
  durable specs, design docs and regression checks as decisions are accepted.

No version bump, final tag, merge, publication or release date is authorized by this plan alone.
