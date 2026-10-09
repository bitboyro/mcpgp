# Guide: building MCP servers in a pipeline

You have OpenAPI specs and want a reliable, reviewed MCP server package out of CI every time. The
pattern: **a git repository is the source of truth; CI validates, diffs, builds, tests, signs and
publishes.** The platform is optional: it can link the same repo as a git source.

## 1. Set up a definitions repository

One repo for one server, or one repo for several servers that share policies:

```
mcp-servers/
├─ .mcpgp/config.yaml              CLI version, servers, output, signing
├─ workspace/                      shared naming policy, rule sets (mcpgp:// references)
├─ servers/
│  ├─ orders/
│  │  ├─ orders.mcpgp.yaml         definition
│  │  ├─ contracts/                the OpenAPI specs, committed
│  │  ├─ overrides/                your rules
│  │  ├─ mcp-names.lock            committed; keeps tool names stable
│  │  └─ tests/                    tool test cases (optional)
│  └─ orders-readonly/
│     └─ orders-readonly.mcpgp.yaml   variant: extends orders
└─ .github/workflows/mcp.yml       (or .gitlab-ci.yml)
```

Start it with:

```bash
mcpgp init --from-spec ./orders-2.0.yaml
```

Full example: [examples/pipeline](../../examples/pipeline).

## 2. Day-to-day flow

```
change spec or rules ─► pull request ─► CI: validate · diff · build · test ─► review ─► merge
                                                                                         │
                                              tag v1.5.0 ─► CI: build · test · sign · publish
```

**On every pull request**
1. `mcpgp validate --all`: the definition and rules are valid.
2. `mcpgp diff --all --against last-release --format md`: posted as a PR comment
   ("+3 tools, 1 removed, 12 descriptions changed").
3. `mcpgp build --all --frozen-lock`: fails if a tool name would change without being accepted.
4. `mcpgp test --all`: starts each built server against a mock generated from the specs and runs
   `tools/list` plus your test cases.

**On a release tag**
1. Same steps, with `--version` from the tag.
2. `mcpgp sign dist/`, then publish the zip (release assets, artifact store, or container
   registry for the Docker image). Optionally `mcpgp push` to register the build on the platform.

## 3. What keeps it reliable

| Risk | Guard |
|---|---|
| Different CLI behaviour between runs | CLI version pinned in `.mcpgp/config.yaml`; container image pinned by digest |
| Spec changed under you | Specs committed in the repo; URL sources need a checksum |
| Tool silently renamed | `mcp-names.lock` + `--frozen-lock`; renames need `mcpgp names accept` in the PR |
| Unreviewed tool changes | `diff` against the last release posted on every PR |
| Build not reproducible | Byte-identical output for the same inputs (`SOURCE_DATE_EPOCH`) |
| Package tampered with | Signed `SHA256SUMS`; customers run `verify` |
| Secrets leaking | CI needs no upstream credentials at all: tests use mocks ([ADR-0011](../decisions/0011-platform-never-calls-targets.md)). Only the signing key and registry credentials are CI secrets |

## 4. When the upstream API publishes a new spec

1. Open a PR that replaces or adds the contract file (a scheduled job can do this from an
   allow-listed URL and open the PR automatically).
2. The PR comment shows exactly what changes for agents: new tools, changed parameters, tools that
   disappear, names that would drift.
3. Adjust rules if needed, accept intended renames, merge, tag.

## 5. Tool test cases (optional)

```yaml
# servers/orders/tests/smoke.yaml
cases:
  - name: list customers works
    tool: orders_customer_list
    args: { instance: mock, query: { account: "a-1", limit: 5 } }
    expect: { ok: true }
  - name: delete asks for confirmation
    tool: orders_customer_delete
    args: { instance: mock, path: { id: "c-1" } }
    expect: { code: CONFIRMATION_REQUIRED }
  - name: unknown instance is rejected
    tool: orders_customer_get
    args: { instance: nope, path: { id: "c-1" } }
    expect: { code: INSTANCE_UNKNOWN }
```

In tests, the mock target is always called `mock` and answers with the spec's examples.

## 6. Using the platform at the same time

Link the repo on the platform as a **git source** (Sources page). Then:
- the platform shows previews, diffs and builds for every branch and tag;
- edits made in the web UI become **commits on a branch with a PR**, never direct changes, so
  git stays the source of truth and the same CI checks apply.
