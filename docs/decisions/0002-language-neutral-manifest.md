# 0002. One compiler, language-neutral manifest

- Status: Accepted
- Date: 2026-10-09

## Context

Java first, other languages later. Re-implementing OpenAPI parsing, naming and rules per language
would multiply bugs and make outputs differ.

## Decision

OpenAPI is parsed only by the compiler. The compiler emits a JSON **manifest** with fully resolved
tools, schemas, names (with provenance) and policies. Runtimes (and code generators) consume only
the manifest. A shared **conformance suite** (fixtures → expected manifest → expected MCP traffic)
applies to every runtime.

## Consequences

- New runtimes are small: manifest interpreter + HTTP invoker + MCP adapter.
- The manifest schema becomes a public contract and is versioned.
- Runtime mode in Java may embed the compiler, but must still go through the manifest.

## Alternatives considered

- Parse OpenAPI in each runtime: simpler at first, divergent later.
