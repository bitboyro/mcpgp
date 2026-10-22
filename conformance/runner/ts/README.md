# The release gate (G1)

Packaged mcpgp servers, driven by an MCP client mcpgp didn't write, against an API mock that checks
every request against the contract. A release is published only when this passes. The plan is
`docs/process/release-gate.md` (milestone M9).

Every test ends in a real MCP call through the package, run the way the customer runs it, to the mock:

```
 @modelcontextprotocol/sdk client ──stdio | Streamable HTTP──► the package ──HTTP──► mock (checks the contract)
                                                                  jar:    java -jar <server>.jar (stdio, HTTP)
                                                                  mcpb:   the bundle's manifest.json command, as a desktop client runs it (stdio)
                                                                  docker: the image built from docker/Dockerfile, as INSTALL.md says (HTTP; Linux)
```

## When it runs

| | |
|---|---|
| Version tag `vX.Y.Z` | `publish.yml` runs the gate (`gate.yml`: the Java tests and this suite) first; images and the GitHub release only if it passes, with the report attached |
| Release candidate `vX.Y.Z-rcN` | The gate only; nothing is published |
| By hand | Actions → gate → Run workflow, on any branch, tag or commit; fewer transports or revisions cost less |
| Locally | `npm run gate` below, at no cost in CI minutes |

Pull requests and pushes to `main` don't run it.

## Run it

```bash
mvn -B -q -pl cli -am package -DskipTests      # the CLI that builds the packages
cd conformance/runner/ts
npm ci
npm run gate                                    # everything; the report is in .gate/report/
```

Narrow it with environment variables and Vitest's filters:

```bash
GATE_TRANSPORTS=stdio GATE_REVISIONS=2025-11-25 npx vitest run tests/s3-operations.test.ts -t "kitchen_item_get"
```

| Variable | Default | |
|---|---|---|
| `GATE_FORMATS` | `jar,mcpb,docker` | docker runs only on Linux with a Docker daemon; elsewhere it is left out unless asked for |
| `GATE_TRANSPORTS` | `stdio,http` | |
| `GATE_REVISIONS` | every revision the server claims | `2025-03-26,2025-06-18,2025-11-25` |
| `GATE_SEED` | `0` | which branches and enum values generated arguments take |
| `GATE_UPDATE_GOLDEN` | | `1` rewrites the golden surfaces of S2 instead of comparing them; review the diff before committing |
| `MCPGP_CLI_JAR` | `cli/target/mcpgp-cli-*-exec.jar` | the CLI that builds the packages |

Each failure in the report has the command that runs it alone.

## What it checks

| Suite | File | |
|---|---|---|
| S0 Package | `tests/s0-package.test.ts` | Every package starts on every transport and revision, negotiates that revision, lists its tools, and puts nothing but the protocol on the wire |
| S2 Surface | `tests/s2-surface.test.ts` | What each package lists (tools, resources, prompts) equals its reviewed golden file in `conformance/fixtures/golden/` on every format, transport and revision; every schema is a valid JSON Schema; each exposure mode (plain with search, discovery search and catalog, toolsets, hybrid) offers the same operations as the plain build, found and called its own way |
| Conformance | `tests/conformance.test.ts` | Every `conformance/cases/**/traffic.yaml`, against the package over real transports (the Java runner plays the same files in-process) |
| S3 Operations | `tests/s3-operations.test.ts` | For every tool of every fixture: valid arguments made from its input schema reach the API once and the contract accepts the request; one-constraint mutations give `INPUT_VALIDATION` with nothing sent; every documented answer becomes its code with no retry; a dropped connection becomes `UPSTREAM_UNREACHABLE` after one request (a `GET` or `HEAD` is resent once and the agent gets that answer, tools-contract §6), and an unparsable answer becomes its code |
| Self-check | `tests/self-check.test.ts` | The mock flags requests that are wrong in each way it checks, so a green run means something |

Error codes must be among those in `docs/spec/audit-and-errors.md` §3, read from the spec at run time.

## How it's built

| File | |
|---|---|
| `src/packages.ts` | `mcpgp build` per definition, as a builder runs it; keeps the server jar; cached by the definition's files and the CLI |
| `src/server.ts` | Starts the package as a customer would (the jar on stdio or HTTP; the desktop bundle with its user config filled in; the container with a static client key) and connects the SDK client, pinned to the revision under test |
| `src/formats.ts`, `src/matrix.ts` | Which formats a package ships and which transports each runs on; building the image once per package |
| `src/golden.ts` | Golden surfaces: recording, comparing per revision, diffs |
| `src/spec.ts`, `src/validate.ts` | Reads the contracts on its own (Swagger 2.0, OpenAPI 3.0, 3.1) and checks a request's parameters, credentials and body against its operation with Ajv: no code shared with mcpgp's compiler |
| `src/mock.ts` | Answers every operation from its success response, or what a test asks for (any status, a dropped connection, a body that isn't JSON), and keeps every request with its violations |
| `src/traffic.ts` | Plays traffic steps: v1 (`mcp:`) and v2 (`call:`), matchers `startsWith`, `matches`, `absent`, `any` |
| `src/generate.ts` | Arguments from input schemas, and the mutations of S3 |
| `src/report.ts` | `gate-report.html` and `gate-report.json` |

Fixtures live in `conformance/fixtures/definitions/`; a new one is added in `src/fixtures.ts`.
