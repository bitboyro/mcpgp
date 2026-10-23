# Pre-release defect corrections

Baseline: `main` at `20a8ca1`, reviewed 2026-10-23. Implementation branch: `codex/release-defects`.
This ledger separates verified defects from UI/UX and distribution product decisions. It is not a release approval.

| Finding | Status | Correction and evidence |
|---|---|---|
| A01 organization defaults | Fixed | Organization schema explicitly reuses runtime inherited settings. SchemaRulesTest exercises nonempty defaults and invalid limits; ToolPolicyTest loads a real organization file and refuses invalid settings. |
| A02 Enterprise template | Fixed | Audit uses `audit.file`; limits and timeout use `settings.rateLimits.concurrent/perMinute` and `settings.timeoutSeconds`. Empty tool allowlist and required authentication/tool-selection inputs remain explicit. FromSpecApiTest imports and compiles all four presets. |
| A03 template/import contract | Fixed | OpenAPI declares template list and existing-server preview/apply, plus collisions, renames, template and acceptTemplate import parameters. Apply documents replacement semantics and required acceptance. PlatformMcpTest discovers schemas, previews, checks refusal without acceptance, and applies a reviewed choice. |
| A04 version diff contract | Fixed | Existing diff endpoint is declared with from/to selectors. PlatformMcpTest calls the generated tool and checks the compared baseline. |
| A07 retained-result preview | Fixed | ToolSchemas shares deterministic output alternatives between runtime and ToolsList. ToolsListExportTest compares inline/auto/reference in all four exposure modes. |
| A08 session helper preview | Fixed | Build-default session support adds the same session_policy schema to ToolsList and generated tool docs. Runtime overrides and caller/deployment differences are explicitly described. |
| A09 execution guidance | Fixed | Generated agent guide includes enabled batch/retention/session guidance, partial/unknown outcomes, safe recovery, separate receipt/snapshot lifetimes, and policy boundaries. AgentExecutionDocsTest checks enabled/disabled output. |
| A10 absent artifact promise | Fixed | Removed claims that generated packages contain SKILL.md. No optimization skill was added. |
| A11 shipped docs links | Fixed | CLI ZIP and both images include offline ADRs and canonical schemas; source-only links use full-commit-pinned URLs. CI checks links in the extracted CLI distribution. |
| A13 focused regressions | Added | Targeted configuration, real operator-file, generated MCP, preview and documentation regressions accompany the actual defects. This does not claim complete feature/permutation coverage. |
| A14 factual claims | Corrected | Feature matrix separates current implementation, explicit refusal and planned gate evidence. Unsupported options remain visible as refused. |

## A05: intentional omissions and decisions left open

| Existing route | Classification | Treatment in this change |
|---|---|---|
| POST identities/{id}/unlock | Person/admin recovery | Keep out of ordinary agent tools; whether to include it in public admin REST documentation remains a product decision. |
| GET servers/{id}/versions/{version}/files/{*path} | Existing raw project-file read | Generic getVersion already returns project files. Dedicated large-project/file inspection deserves an explicit public-contract decision; no blanket MCP exposure added. |
| GET library/{kind}/{name}/versions/{version}/files/{*path} | Existing raw Library-file read | Same explicit public-contract decision; generic Library version retrieval remains available. |
| POST servers/{id}/versions/{version}/try/warm | Internal UI prewarming | Intentionally absent from ordinary MCP tools; tryTool performs the user-visible operation. |
| GET openapi.json | Contract discovery | Intentionally not a business MCP tool. |

UI redesign, Library template UX, navigation, wording-wide changes, baseline instruction editing,
AI optimization, tutorials, fleet management and standalone platform release assets remain separate decisions.

## Verification and limits

Full `mvn -q verify` completed successfully: 523 tests reported, 511 passed, 12 skipped,
zero failures/errors. Skips: 11 PostgreSQL-store tests without a configured PostgreSQL database,
and the opt-in self-contained/strace package check. No PostgreSQL integration result is claimed.


Focused tests: 35 passed (SchemaRulesTest 9; ToolPolicyTest 10; ToolsListExportTest 3;
AgentExecutionDocsTest 1; FromSpecApiTest 8; PlatformMcpTest 4).
The platform tests use generated MCP handlers calling the real local platform HTTP server; they
are not the independent TypeScript MCP-client release gate.

An initial `mvn test` run could not download the platform MCP package because the fresh worktree
had no embedded runtime jar. Building the reactor through `package` supplied that prerequisite;
the subsequent focused run passed. This was build lifecycle setup, not a product fix.

Documentation layout verification assembled and extracted the CLI ZIP built from this branch:
47 Markdown files and 95 relative file links, zero missing destinations. Local lychee
was unavailable; the extracted-distribution lychee check is in CI. Images, cross-platform launchers,
all preset packages with real operator credentials, and the independent release gate were not run here.
