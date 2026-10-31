# European MCP catalogue: implementation plan

Proposed execution plan, 2026-10-31. Agreed direction: one example repository, individual API
servers and profession-focused multi-API bundles, monthly builds/releases and weekly vulnerability
checks. [Verified source inventory](european-api-inventory.md) records evidence and limits.
This document does not publish packages or activate scheduled workflows.

## Product and architecture

Repository: [mcpgp-catalog](https://github.com/bitboyro/mcpgp-catalog), with Europe as the first collection. The repository is an example consumer of released MCP GP,
with definitions, upstream OpenAPI snapshots, overlays, instructions, configuration examples,
tests and CI. No handwritten per-provider runtime adapters in the initial collection.

Each API has one maintained integration source. Single servers and bundles consume shared
specifications and reusable definition components. Verify supported reference/merge mechanisms
before choosing exact file layout; do not invent a bundle format or assume inheritance combines
arrays correctly. The existing multi-API model is described in ADR-0009.

Suggested organization:

- catalogue/: API IDs, geography, profession tags, evidence, licenses, maturity and provenance.
- apis/<id>/: upstream specification, checksum, source URL, overrides and provider notes.
- servers/<id>/: single-API MCP GP definition and configuration example.
- bundles/<id>/: multi-API MCP GP definition, selected operations and shared instructions.
- tests/: mock fixtures, protocol cases and separately enabled live probes.
- docs/: generated catalogue, install instructions and task examples.
- .github/workflows/: validation, release and security workflows.

This is a proposed directory layout, not a new definition-language syntax.
Keep orchestration scripts generic. Business behavior belongs in supported definitions; identify
platform gaps explicitly if a desired tool would require custom code.

## Stages and exit criteria

| Stage | Work | Exit evidence |
|---|---|---|
| 1. Verify sources | Extend inventory country by country; inspect exact operations, auth, quotas, schema format and reuse conditions | Each candidate has an ID, evidence date/URLs, scope and unresolved questions |
| 2. Prove generation | French company search, Norwegian register, NBP and French geography first; then ANAF, TED, Eurostat, Companies House and TfL | Pinned specs compile using a released MCP GP version; generated packages initialize and pass representative tool calls against mocks; one-command reproduction. Local proof for the four anonymous REST integrations: [mcpgp-catalog](https://github.com/bitboyro/mcpgp-catalog) |
| 3. Establish artifact security | Inspect delivered dependency contents; implement or identify missing artifact SBOM/scanning support in MCP GP | Per-artifact SBOM matches contents; vulnerability results, checksums and build provenance tied to artifact hashes |
| 4. Publish individual integrations | Add documentation, credentials setup, supported scope and bounded live probes | Every published server passes its own checks; skipped credential checks visible; installation tested from the actual release asset |
| 5. Compose profession bundles | Reuse verified APIs; curate tools, prefixes, instructions and configuration | Cross-API credential isolation, naming collisions, tool access and representative multi-source tasks tested |
| 6. Automate upkeep | Monthly reproducible builds/releases, weekly scans, reviewable update PRs | Release manifest records each artifact and failed/skipped item; failed checks prevent promotion; security findings reach maintainers |
| 7. Expand | Admit additional verified candidates in small batches | Same publication bar as the initial set; catalogue coverage never substitutes for working artifacts |

Research scope is broad; the initial 0.5.0 acceptance proposal is four verified individual servers,
one useful multi-API bundle and the working delivery/security process. The original six regional
integrations remain prioritized, but credentials or upstream availability can affect order.
Agree the final release acceptance count during review; this plan does not silently require all
29 inventory entries before releasing MCP GP.

## Bundle proposals

| Bundle | Initial sources / IDs | User outcome and boundary |
|---|---|---|
| Company research | EU01, EU02; then EU03, EU04 | Retrieve public records across supported countries; keep national identifiers and dates. No automatic cross-country entity match claim |
| Procurement research | EU05 plus EU01–EU04 as available | Find EU notices and investigate named organizations; agent performs lookups, server does not imply a consolidated ownership database |
| Economic research | EU13–EU19 | Compare selected official indicators; expose units, periods, revisions and definitions; national series are not automatically comparable |
| Accounting research | EU04, EU15, EU20; later EU14 | Public company/VAT lookups and published exchange rates; clearly identify source/date and quotation conventions |
| Legal research by jurisdiction | Spain EU06; France EU07+EU08; Poland EU09 | Retrieve source material with jurisdiction, document identity and version/date. EU12 stays held; parliamentary records EU10/EU11 are labelled separately |
| Environment and energy | EU21–EU26, with EU28 where useful | Weather, air quality, biodiversity and energy observations; clear coverage, time and units, no invented aggregate risk score |
| London travel | EU27 + EU21 | Journey/arrival information with weather; no claim of pan-European transport coverage |

Publish only bundles with verified components and a useful task. A one-source legal server remains
an individual server until a meaningful bundle exists. Credentials are supplied by the operator
per provider. Missing credentials must not cause silent partial answers; document whether a
configured bundle refuses startup or omits unavailable tools, based on verified runtime behavior.

## Integration acceptance

Track independent evidence dimensions: provider docs verified, schema retrieved/authored,
compile pass, mocked protocol pass, live check pass/skipped, packaging pass, license review and
security result. A single green label must not hide a missing credential check.

For each selected operation:

- Record upstream URL/version, specification origin/license and content checksum; distinguish
  provider-authored contracts from contracts we maintain.
- Record permitted use, attribution, caching, limits and restrictions from provider terms.
  Do not redistribute full datasets merely because the wrapper is open source.
- Define a stable tool name, description, input constraints and bounded result behavior.
- Cover representative success, empty result, invalid input, upstream error, authentication
  failure where applicable, pagination and response limits.
- Keep upstream content as data; avoid instructions that grant authority to retrieved text.
- Test the installed release artifact, not only the source or compiler output.

## Monthly delivery and weekly security

Proposed schedule: build on the first day of each month at 06:00 UTC; scan released artifacts
every Monday at 06:00 UTC. Manual runs remain available. These schedules are not activated.

Update discovery opens PRs for MCP GP version changes and upstream specification changes.
Accepted commits pin the generator, specs, dependencies and build environment. Monthly release
jobs consume those pins, never silently fetch a new specification or expand exposed operations.

Build each single server and bundle in an independent matrix job. Keep deterministic mock
tests in required CI; live probes run separately with provider-specific budgets and credentials.
An upstream outage is reported as such, not rewritten as a passing test. Never send writes to
live government services; a read-only POST search is allowed when documented.

Each artifact carries its definition/spec hashes, generator version, source commit, configuration
guide, license/attribution notices, checksums, SBOM, test result and scan result. Check transitive
runtime libraries and extensions; container artifacts also include base-image/OS inventory.
Verify what MCP GP already supplies before adding delivery features.

Publish a monthly catalogue manifest containing every intended artifact and its disposition.
Failed artifacts are excluded from new downloads and shown as failed; retain the prior release
with its original date. A bundle is promoted only if all its selected integrations pass.
Block newly shipped critical/high findings unless a documented assessment records a scoped,
time-limited exception. Weekly scans target immutable published artifact digests, not only
the current repository dependency graph; fixes can trigger an out-of-cycle release.

## Next review

Review the inventory and the first bundle's tool scope. Then prove the four anonymous REST
integrations before creating the full catalogue. Credentials and account registrations are needed only
for the integrations that require them; independent public integrations can proceed.
