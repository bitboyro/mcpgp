# Guide: building MCP servers in a pipeline

You have OpenAPI specs and want a reliable, reviewed MCP server package out of CI every time. The
pattern: **a git repository is the source of truth; CI validates, builds, tests, signs and
publishes.** The platform is optional: CI can push the same definitions to it (§6).

## 1. Set up a definitions repository

One repo for one server, or one repo for several servers that share policies:

```
mcp-servers/
├─ .mcpgp/config.yaml              CLI version, servers, workspace, output, signing
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

`.mcpgp/config.yaml` ([cli.md §2](../spec/cli.md#2-repository-config-mcpgpconfigyaml)):

```yaml
cli: 0.2.1                         # the exact CLI version; another version refuses to run (exit 5)
workspace: workspace
servers:                           # what --all works on
  - servers/orders/orders.mcpgp.yaml
  - servers/orders-readonly/orders-readonly.mcpgp.yaml
out: dist
sign:
  key: ${env:MCPGP_SIGNING_KEY}    # for mcpgp sign
```

Start a server with:

```bash
mcpgp init --from-spec ./orders-2.0.yaml
```

Get the CLI from the release (`mcpgp-cli-<version>.zip`, Java 21+), or use the CLI image
`docker.io/<account>/mcpgp-cli:<version>` in CI ([cli.md §1](../spec/cli.md#1-installation-and-pinning)).
Full example: [examples/pipeline](../../examples/pipeline).

## 2. Day-to-day flow

```
change spec or rules ─► pull request ─► CI: validate · build · test ─► review ─► merge
                                                                                 │
                                         tag v1.5.0 ─► CI: build · test · sign · publish
```

**On every pull request**
1. `mcpgp validate --all`: the definitions and rules are valid.
2. `mcpgp build --all --frozen-lock`: packages every server; fails (exit 3) if a tool name would
   change without being accepted with `mcpgp names accept`.
3. `mcpgp test --all`: starts each server against a mock made from its specs and runs `tools/list`
   plus the test cases in its `tests/` folder.
4. **TODO (not yet):** `mcpgp diff --all --against last-release --format md` as a PR comment
   ("+3 tools, 1 removed, 12 descriptions changed"). Until then, reviewers read the definition
   changes and the build log.

**On a release tag**
1. The same steps. The server version follows the API (contract) version; `--version` overrides it.
2. `mcpgp sign dist/`: a `SHA256SUMS` over the files in `dist/` and its signature, for what you
   publish. Packages are signed inside as well when the definition has `build.sign.key`. Customers
   check both with `mcpgp verify` and your public key.
3. Publish `dist/` (release assets, artifact store). Optionally `mcpgp push` registers the build on
   the platform (§6).

## 3. What keeps it reliable

| Risk | Guard |
|---|---|
| Different CLI behaviour between runs | CLI version pinned in `.mcpgp/config.yaml` (exit code 5 on a mismatch); the CLI image pinned by digest |
| Spec changed under you | Specs committed in the repo |
| Tool silently renamed | `mcp-names.lock` + `--frozen-lock`; renames need `mcpgp names accept` in the PR |
| Unreviewed tool changes | Definition and lock changes reviewed in the PR (**TODO:** `diff` posted on every PR) |
| Build not reproducible | Byte-identical output for the same inputs (`SOURCE_DATE_EPOCH`, else the commit time) |
| Package tampered with | Signed `SHA256SUMS` in each package and over `dist/`; customers run `mcpgp verify` |
| Secrets leaking | CI needs no upstream credentials at all: tests use mocks ([ADR-0011](../decisions/0011-platform-never-calls-targets.md)). Only the signing key and the platform token are CI secrets |

## 4. When the upstream API publishes a new spec

1. Open a PR that replaces or adds the contract file.
2. CI shows what breaks: validation errors, names that would drift (`--frozen-lock`), failing test
   cases. **TODO:** the `diff` comment listing new tools, changed parameters and removed tools.
3. Adjust rules if needed, accept intended renames (`mcpgp names accept`), merge, tag.

## 5. Tool test cases (optional)

```yaml
# servers/orders/tests/smoke.yaml
cases:
  - name: list customers works
    tool: orders_customer_list
    args: { instance: mock, query: { accountId: "a-1", limit: 5 } }
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

In tests, the mock target is always called `mock` and answers with the spec's examples (or values
made from the response schemas). `instance: mock` is dropped for single-instance builds, so the same
cases work for both. Details: [cli.md §3.2](../spec/cli.md#32-test).

## 6. Using the platform at the same time

### 6.1 Pushing to the platform

When the platform builds and releases, CI hands it the definition after the checks:

```yaml
      - name: Push to the platform
        if: github.ref == 'refs/heads/main'
        env:
          MCPGP_PLATFORM: https://mcpgp.example.internal
          MCPGP_TOKEN: "${{ secrets.MCPGP_TOKEN }}"     # a pipeline identity's token
        run: mcpgp push servers/orders/orders.mcpgp.yaml --build --verify-local --message "${{ github.event.head_commit.message }}"
```

The platform keeps the version, builds and signs it; `--verify-local` fails the job if its build
differs from what CI tested. Add `--release` to release it as well. Only the files the definition
uses are sent ([cli.md §3.3](../spec/cli.md#33-push)).

### 6.2 Git sources (TODO, not yet)

Planned: link the repo on the platform as a **git source** (Sources page). Then the platform would
show previews and builds for every branch and tag, and edits made in the web UI would become
**commits on a branch with a PR**, never direct changes, so git stays the source of truth and the
same CI checks apply. Until then, use `mcpgp push` (§6.1).
