# Development process

How we go from an idea to a merged change. Applies to people and to coding agents.

## 1. Principles

1. **Spec first.** Behaviour is defined in `docs/spec` before it is implemented. A change in
   behaviour starts with a spec change in the same PR (or an earlier one).
2. **Decisions are recorded.** Anything that changes a spec, a public contract (definition format,
   runtime config, manifest, tool contract, audit/error codes) or the architecture needs an ADR.
3. **Examples are tests.** Every file in `examples/` must validate against the schemas and produce
   the expected manifest. A spec change updates the examples in the same PR.
4. **Small, reviewable changes.** One concern per PR. Prefer several small PRs to one large one.
5. **Contracts are stable.** Breaking a public contract requires a new `apiVersion` (or major
   version), a migration and an ADR.

## 2. Flow

```
idea / issue ──► (if it changes behaviour) spec PR + ADR ──► implementation PR(s) ──► release
```

1. **Issue.** Use a template: feature, bug, spec change, or decision. Link the requirement in
   `docs/requirements.md` (and its priority) when there is one.
2. **Spec PR** (when behaviour changes): update `docs/spec/*`, add or supersede an ADR, update
   `docs/requirements.md` and `examples/`. Label `spec`. Needs approval from the spec owner.
3. **Implementation PR**: code + tests + conformance fixtures. Must not change behaviour beyond
   what the spec says. Label by area (`core`, `runtime`, `packager`, `platform`, `web`, `docs`).
4. **Review & merge.** Squash-merge to `main`.
5. **Release** (see §7).

## 3. Branches and commits

- Trunk-based: `main` is always releasable. Short-lived branches, rebased on `main`.
- Branch names: `<type>/<short-description>`, e.g. `spec/naming-limits`, `feat/compiler-resolver`,
  `fix/lockfile-drift`.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org):
  `feat(core): resolve include globs`, `fix(runtime): redact apiKey in audit`,
  `docs(spec): define collision suffixes`. Breaking changes: `feat(core)!: …` plus a `BREAKING CHANGE:` footer.
- Squash-merge; the PR title becomes the commit message, so it follows the same format.

## 4. Definition of done

A change is done when:

- [ ] Spec and ADR updated if behaviour or a contract changed
- [ ] `docs/requirements.md` updated (new requirement, changed priority, or status)
- [ ] Examples updated and validating
- [ ] Unit tests for new logic; conformance fixtures for anything visible in the manifest or MCP traffic
- [ ] No secrets, credentials or customer data in code, fixtures, logs or examples
- [ ] Generated docs (README/INSTALL/…) still correct for the example builds
- [ ] CHANGELOG entry under "Unreleased" for user-visible changes
- [ ] Reviewed and approved; CI green

## 5. Reviews

- At least **one approval**; **two** for changes to a public contract or security-sensitive code
  (auth, redaction, secret handling, file resolution, network access).
- Reviewers check the change against the spec, not against personal preference.
- Coding agents may open PRs; a person approves and merges.

## 6. Testing strategy

| Level | What | Where |
|---|---|---|
| Unit | Pure logic: naming steps, merge rules, selectors, redaction | next to the code |
| Golden / snapshot | Definition → manifest for every example and fixture | `conformance/manifests` |
| Conformance | Manifest → expected `tools/list` and `tools/call` traffic against a mock upstream; run for every runtime language | `conformance/` |
| Contract | JSON Schemas validate examples; schemas themselves are linted | `schemas/` |
| Wire | Every multipart, binary-upload and binary-download tool called against a capture mock; asserts `Content-Type`, `Accept`, body size, part names (tools-contract §13.5). Runs for the runtime and every generated client | `conformance/wire` |
| Integration | Built package started with an example runtime config against a mock server generated from the spec | CI job |
| Security | Path traversal, SSRF allowlist, redaction, `allowInsecure` guardrail, secret references | dedicated suite |
| Store | The same platform tests on both stores: `FileStoreTest` / `PostgresStoreTest` (the store contract), and every platform API test again on PostgreSQL when `MCPGP_TEST_POSTGRES_URL` is set | `platform/`, CI job `postgres` |
| Deployment | The image and Docker Compose with PostgreSQL and signing, checked with `deploy/smoke-test.py`, including a restart | CI job `deploy` |

Rules: no test may call a real external system; mocks come from spec examples or recorded fixtures
with secrets removed.

The platform tests on PostgreSQL, locally (any throwaway database; each test class gets its own schema):

```bash
docker run -d --name mcpgp-pg -e POSTGRES_USER=mcpgp -e POSTGRES_PASSWORD=test -e POSTGRES_DB=mcpgp -p 55432:5432 postgres:17-alpine
MCPGP_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:55432/mcpgp MCPGP_TEST_POSTGRES_PASSWORD=test mvn -pl platform test
```

## 7. Versioning and releases

- **Platform and CLI:** SemVer. `0.x` until the definition format is accepted as v1.
- **Definition format:** `apiVersion: mcpgp/vN`; breaking changes only in a new N with automatic
  migration (`mcpgp migrate`).
- **Manifest, runtime config, audit event:** each has its own version field; additive changes only
  within a version.
- **Release steps:**
  1. A release PR: the version in every `pom.xml` and in `Compiler.VERSION` set to `X.Y.Z` (no
     `-SNAPSHOT`), the CHANGELOG's Unreleased section renamed `## X.Y.Z — <date>`, breaking changes
     and migrations listed first. Merge it once CI is green.
  2. Tag that commit `vX.Y.Z` and push the tag. The `publish` workflow checks the tag matches the
     version, pushes the image (`:X.Y.Z`, `:X.Y`, `:latest`) and creates the GitHub release from the
     CHANGELOG section.
  3. A follow-up PR sets the next version, `X.Y+1.0-SNAPSHOT`.
- **Images:** every push to `main` publishes `:main` and `:sha-<commit>`. Images of other branches
  only on request: Actions → publish → Run workflow, on the branch (tag: the branch name, or one you
  type in). Pull requests never publish.

## 8. Working with coding agents

- Agents read `docs/README.md`, the relevant spec and ADRs before changing anything.
- Agents follow the same flow: spec/ADR first for behaviour changes, small PRs, conventional commits.
- Agents never commit secrets, never run against real customer systems, never merge their own PRs.
- When an agent finds the spec ambiguous, it opens a `spec` issue instead of guessing.

## 9. Documentation upkeep

- `docs/requirements.md` is the backlog's source of truth for scope and priority.
- Superseded specs are not deleted; their ADR is marked superseded and the spec text is updated.
- UI changes update `docs/design/ui.md` and the design canvas link.
