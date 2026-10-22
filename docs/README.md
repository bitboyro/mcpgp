# Documentation

| Document | Read it when |
|---|---|
| [concepts.md](concepts.md) | You're new. Defines every term used elsewhere. |
| [requirements.md](requirements.md) | You need to know whether something is in scope, and when (P0/P1/P2). |
| [architecture.md](architecture.md) | You're about to design or implement a component. |
| [spec/definition-format.md](spec/definition-format.md) | You write or parse `*.mcpgp.yaml`. |
| [spec/manifest.md](spec/manifest.md) | You produce or consume the compiler's output (runtimes, codegen). |
| [spec/extensions.md](spec/extensions.md) | You write custom tools, enrichers or call filters. |
| [spec/conformance.md](spec/conformance.md) | You add test cases or build a runtime. |
| [spec/naming.md](spec/naming.md) | You touch tool names, titles or descriptions. |
| [spec/runtime-config.md](spec/runtime-config.md) | You write or parse the customer's runtime config. |
| [spec/layered-runtime-policy.md](spec/layered-runtime-policy.md) | You implement layered tool restrictions, live policy updates or configuration templates (implementation status and remaining stages). |
| [spec/runtime-policy-management.md](spec/runtime-policy-management.md) | You administer runtime tool policies through authenticated preview/apply transactions. |
| [spec/auth.md](spec/auth.md) | You touch inbound or downstream authentication. |
| [spec/tools-contract.md](spec/tools-contract.md) | You need to know exactly what an agent receives. |
| [spec/audit-and-errors.md](spec/audit-and-errors.md) | You emit audit events, errors or telemetry. |
| [spec/packaging.md](spec/packaging.md) | You build or deliver packages, or register clients. |
| [spec/cli.md](spec/cli.md) | You script the CLI or run it in CI. |
| [spec/platform-api.md](spec/platform-api.md) | You work on the platform: store, builds, identities, the REST API. |
| [guides/ci-pipeline.md](guides/ci-pipeline.md) | You want reliable builds from a git repo in CI. |
| [deployment.md](deployment.md) | You plan a platform installation: container, store, network, sign-in, backups, upgrades. |
| [guides/self-hosting.md](guides/self-hosting.md) | You install the platform with Docker Compose, step by step. |
| [guides/platform.md](guides/platform.md) | You run the platform: sign-in, the web UI, the API. |
| [guides/new-api-version.md](guides/new-api-version.md) | Your API has a new version: what you do, and what your customers do. |
| [guides/agents.md](guides/agents.md) | An agent should build MCP servers on the platform. |
| [guides/extensions.md](guides/extensions.md) | A server needs a bit of custom code. |
| [spec/compatibility.md](spec/compatibility.md) | MCP or OpenAPI publish a new version, or you change one of our formats. |
| [security.md](security.md) | You need the threat model or answer a security questionnaire. |
| [decisions/](decisions) | You want to know *why* something is the way it is. |
| [design/ui.md](design/ui.md) | You work on the web UI. |
| [process/development.md](process/development.md) | Before your first change. |
| [process/roadmap.md](process/roadmap.md) | You want to know what comes next. |
| [process/release-gate.md](process/release-gate.md) | You are working on the release gate. |
| [process/feature-matrix.md](process/feature-matrix.md) | You need every feature, its settings and what tests it. |

Examples that follow these specs live in [../examples](../examples).

**Rule:** specs are normative. When code and a spec disagree, one of them is a bug, and the
fix starts with a change to the spec (see [process/development.md](process/development.md)).
