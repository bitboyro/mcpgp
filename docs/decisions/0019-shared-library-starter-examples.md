# ADR 0019: versioned Shared Library starter examples

Status: accepted · Date: 2026-10-24

## Context

The Shared Library starts empty although contract-import presets already exist. Users cannot
inspect reusable examples and understandably expect the presets under Templates.

## Decision

On platform startup, create missing version-1 examples in all six Library categories: contracts,
naming, rules, notices, packs and templates. Existing names, content and versions are never
replaced, including on upgrades. Concurrent startup tolerates another instance creating an item.
Examples are real stored Library items and use normal versioned references and access controls;
they are not automatically applied to an MCP server. Their initial author is `mcpgp-examples`.

Template display names are Bare, Default, Basic and Enterprise. Existing API preset IDs remain
`blank`, `default`, `personal-use` and `enterprise-grade` for compatibility. Stored starter templates
are generated from the same built-in definitions. The template catalogue suppresses duplicate
pristine starter copies; a user-versioned copy appears as a Library template.

OpenAPI 3.1 defines the example API structure. MCP defines tool annotation meanings; annotations
and notices are not authorization enforcement. Naming style and presets are explicit mcpgp
conventions. Enterprise is an operator-reviewed starting point, not a compliance certification.

## Consequences

Fresh and existing workspaces gain examples without overwriting their configuration. Every Library
entry can be opened: naming/notices retain their editors, other kinds get a read-only source view.
Built-ins and edited Library templates may intentionally diverge. Subsequent example revisions
need a separate upgrade policy; startup does not silently update them.
