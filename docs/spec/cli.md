# Spec: `mcpgp` CLI

Status: **draft v1** · Owner: cli

The CLI does everything the platform does with a definition, locally, without the platform. It is what
pipelines run. The web UI and the REST API use the same compiler and packager.

## 1. Installation and pinning

| Form | Use |
|---|---|
| Container image `mcpgp:<version>` | CI (recommended): nothing to install, version pinned by tag + digest |
| Single binary / jar, with `SHA256SUMS` | Developer machines, air-gapped CI |

Pin the version in the repo: `.mcpgp/config.yaml` → `cli: 0.4.2`. The CLI refuses to run with a
different version unless `--ignore-version` is given.

## 2. Repository config (`.mcpgp/config.yaml`)

```yaml
cli: 0.4.2                     # exact CLI version this repo is built with
workspace: workspace           # local folder for mcpgp:// references (or `platform: <url>`)
servers:                       # definitions in this repo
  - servers/orders/orders.mcpgp.yaml
  - servers/orders-readonly/orders-readonly.mcpgp.yaml
out: dist
release:
  versionFrom: contract        # contract (default: the API version) | git-tag | file:VERSION | flag
  baseline: last-release       # what `diff` compares against
sign:
  key: ${env:MCPGP_SIGNING_KEY}
```

## 3. Commands

| Command | Does |
|---|---|
| `mcpgp init [--from-spec <file\|url>] [--name <n>] [--out <dir>]` | Scaffold a definition project: the definition, the spec under `contracts/`, and `tests/smoke.yaml` with cases for read tools that need no arguments (§3.1) |
| `mcpgp validate [<def>…]` | Schema + compiler checks; no output files |
| `mcpgp preview <def> [--tools] [--customer] [--format md\|json]` | What the agent gets / what the customer configures |
| `mcpgp explain <def> <tool>` | Provenance of a tool's name, title, description |
| `mcpgp diff <def> [--against <tag\|manifest.json\|last-release>] [--format md\|json]` | Added, removed and changed tools, schemas and names (informational) |
| `mcpgp names accept <def> <tool…\|--all>` | Accept renames into `mcp-names.lock` |
| `mcpgp build <def…\|--all> [--version <v>] [--build-number <n>] [--source <uri>] [--frozen-lock] [--manifest-only] [--out <dir>]` | Compile + package: `dist/<name>-<version>.zip` and `dist/<name>-<version>.manifest.json`; `--manifest-only` writes only the manifest |
| `mcpgp test <def> [--suite tests/]` | Start the server against a mock made from the specs; run a smoke check and the test cases (§3.2) |
| `mcpgp keygen [--out <dir>]` | Create a signing key pair (`mcpgp.key`, secret; `mcpgp.pub`, to publish) |
| `mcpgp verify <zip> --key <pub>` | Check the signature of `SHA256SUMS` and every file against it; extra or changed files fail |
| `mcpgp migrate <def>` | Upgrade a definition to the current `apiVersion` |
| `mcpgp try <def> --target <url>` | Run the server locally against a real target the developer's machine can reach (never in CI by default) |
| `mcpgp push <def> [--platform <url>] [--server <id>] [--message <text>] [--build] [--verify-local] [--release]` | Upload the definition project to a platform as its server's next version; optionally build, compare and release it there (§3.3) |

Global flags: `--config`, `--workspace`, `--json` (machine-readable output), `--quiet`, `--no-color`.

### 3.1 `init`

`--from-spec` takes a file or an `https` URL (plain `http` only for localhost) of an OpenAPI 3.x or
Swagger 2.0 document. The API id comes from the spec's title ("Orders API" → `orders`), the server
name adds `-mcp` (or `--name`), the contract version is `info.version`, and instances start in
`single` mode. Existing files are never overwritten. Without `--from-spec`, the definition points at
`contracts/openapi.yaml` for the developer to fill in.

### 3.2 `test`

1. Compiles the definition (errors stop here, exit code 1).
2. Starts a **mock target** on the loopback address. It answers every operation of the contracts with
   its first success response: the media type's `example` (or first of `examples`) when the spec has
   one, otherwise a value made from the response schema (`example`, `default`, `const`, first `enum`
   value, else by type and format). It answers nothing else and calls nothing.
3. Starts the runtime the CLI packages (the same jar as in the package) in stdio mode with the
   compiled manifest and every API's instance at the mock; in multi-instance builds the instance is
   called `mock`.
4. Smoke check: the MCP session starts and `tools/list` lists tools.
5. Runs each `*.yaml` in the suite (default: `tests/` next to the definition), in name order:

```yaml
cases:
  - name: refund returns the order
    tool: orders_order_refund
    args: { instance: mock, path: { id: "o-1001" }, body: { amount: 25 } }
    expect: { ok: true, structured: { status: refunded } }   # structured: a partial match
  - name: unknown instance is rejected
    tool: orders_customer_get
    args: { instance: nope, path: { id: "c-1" } }
    expect: { code: INSTANCE_UNKNOWN }
```

`instance: mock` is dropped for tools without an `instance` argument (single-instance builds), so one
suite fits both. `structured` is matched against the structured content, or the text content when it
is JSON. Any failed case: exit code 4.

### 3.3 `push`

Sends the definition **project**, not a package: the platform builds, signs and keeps every version
with the same compiler, so there is one way for packages to come about.

- Only the files the definition uses are sent (the definition, its overrides and other included
  files, contracts and overlays, `mcp-names.lock`); keys, tests and anything else in the folder stay
  local. `mcpgp://` references resolve from the platform's library.
- The server is `metadata.name` (or `--server`); a server the platform doesn't have is created. The
  same content as the latest version adds no version.
- The token comes from `MCPGP_TOKEN` (never a flag, to keep it out of shell history and CI logs); the
  address from `--platform` or `MCPGP_PLATFORM`, `https` (plain `http` only for localhost).
- `--build` starts a build of the new version and waits (`--timeout`, default 600 s); a failed build
  prints the end of its log, exit code 1.
- `--verify-local` (implies `--build`): the platform's manifest must give agents the same as the
  local compile — equal digests of the manifest without its `build` section and the server version's
  build number. A difference usually means the CLI and the platform run different compiler versions.
- `--release` (implies `--build`) releases the build; for an agent's token it requests an approval
  instead, which a person decides (platform-api.md §5).
- `--json` prints `{server, version, created, build, status, serverVersion, released | approval, ok}`.

## 4. Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Validation or build errors |
| 2 | Usage error (unknown command or option) |
| 3 | Name drift with `--frozen-lock` (a name would change; run `names accept`) |
| 4 | Tests failed (`test`) |
| 5 | Version mismatch (CLI vs `.mcpgp/config.yaml`) |
| 10+ | Internal error |

## 5. Versions

The server's version **follows the API version**: by default it is the default contract's version
(contract `2.0` → server `2.0.0`). Rebuilding the same contract version (new rules, fixes) adds a build
number as SemVer build metadata: `2.0.0+3`. `release.versionFrom` or `--version` can override this.

Breaking-change detection between builds is out of scope for now: tool changes come with API versions.

## 6. Reproducibility guarantees

Same CLI version + same definition files + same lock file + same `--version` ⇒ **byte-identical
package** (timestamps from `SOURCE_DATE_EPOCH` or the commit time; sorted archives; no network
access unless a definition references an allow-listed URL, which must carry a checksum).

## 7. Outputs (`dist/`)

```
dist/orders-mcp-1.4.1.zip          the package (see packaging.md)
dist/orders-mcp-1.4.1.manifest.json
dist/orders-mcp-1.4.1.report.md    human build report: tools, warnings, diff
dist/orders-mcp-1.4.1.report.json  same, machine-readable
dist/SHA256SUMS(.sig)
```
