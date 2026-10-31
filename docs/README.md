# MCP GP documentation

Choose the task you want to accomplish. [Concepts and terminology](concepts.md) explains the
builder, server operator and platform administrator roles.

## Get started

- [Get started with the CLI or platform](guides/getting-started.md)
- [Create a server in the platform](guides/platform.md)
- [Explore working examples](../examples/README.md)

## Build a server

- [Define your server and select API operations](spec/definition-format.md)
- [Choose tool names and descriptions](spec/naming.md)
- [Configure client sign-in and target API authentication](spec/auth.md)
- [Build and deliver packages](spec/packaging.md)
- [Automate builds in CI](guides/ci-pipeline.md)
- [Update an API version](guides/new-api-version.md)
- [Let an agent build servers](guides/agents.md)
- [Add custom behavior with an extension](guides/extensions.md)

## Run a server

These pages concern the generated server running at the customer.

- [Configure and start a server](spec/runtime-config.md)
- [Configure credentials and authentication](spec/auth.md)
- [Manage tool access policies](spec/runtime-policy-management.md)
- [Configure identity, organization and session restrictions](spec/policy-sources-and-sessions.md)
- [Understand batching and retained results](spec/batching-and-results.md)
- [Understand audit events and troubleshoot errors](spec/audit-and-errors.md)
- [Check security updates and report vulnerabilities](../SECURITY.md)

## Run the platform

These pages concern the service used to author and build servers.

- [Install with Docker Compose](guides/self-hosting.md)
- [Plan storage, networking, sign-in, backups and upgrades](deployment.md)
- [Use the platform and its API](guides/platform.md)
- [Connect an agent to the platform](guides/agents.md)

## Reference

- [Definition format](spec/definition-format.md) and [naming](spec/naming.md)
- [Runtime configuration](spec/runtime-config.md) and [authentication](spec/auth.md)
- [CLI commands](spec/cli.md) and [platform API](spec/platform-api.md)
- [MCP tool inputs and results](spec/tools-contract.md)
- [Batching and results](spec/batching-and-results.md)
- [Policy management](spec/runtime-policy-management.md) and [policy sources and sessions](spec/policy-sources-and-sessions.md)
- [Audit events and errors](spec/audit-and-errors.md)
- [Package contents](spec/packaging.md), [extensions](spec/extensions.md) and [compatibility](spec/compatibility.md)

## Contribute

[Contributor documentation](contributors/README.md) contains architecture, decisions, development
process, implementation specifications and release-gate internals.

This navigation begins the [0.5.0 documentation review](process/documentation-review.md).
Some task links currently lead to detailed references; shorter task guides will follow during
that review. Existing document paths remain valid.
