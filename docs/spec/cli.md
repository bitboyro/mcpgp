# Spec: `mcpgp` CLI

Status: **draft v1** · Owner: cli

The CLI does everything the platform does with a definition, locally, without the platform. It is what
pipelines run. The web UI and the REST API use the same compiler and packager.

## 1. Installation and pinning

| Form | Use |
|---|---|
| Container image `docker.io/<account>/mcpgp-cli:<version>` (`mcpgp` on the PATH, git included) | CI (recommended): nothing to install, version pinned by tag + digest |
| `mcpgp-cli-<version>.zip` from the GitHub release: `bin/mcpgp` (and `bin/mcpgp.cmd`), `lib/mcpgp-cli.jar`; checksums in the release's `SHA256SUMS` | Developer machines, air-gapped CI. Needs Java 21+ (`JAVA_HOME`, else `java` on the PATH); `MCPGP_JAVA_OPTS` adds JVM options |
| `mcpgp-cli-<version>.jar` from the release | `java -jar mcpgp-cli-<version>.jar …` |
| **TODO:** a single native binary | Without Java |

Pin the version in the repo: `.mcpgp/config.yaml` → `cli: 0.3.0`. The CLI refuses to run with a
different version (exit code 5) unless `--ignore-version` is given. Help (`--help`, `-h`) and
`--version` always work.

## 2. Repository config (`.mcpgp/config.yaml`)

The CLI uses the nearest `.mcpgp/config.yaml` from the working directory upwards, or the file given
with `--config`. Paths in it are relative to the repository root (the folder holding `.mcpgp/`). It
is checked against `schemas/cli-config.schema.json`.

```yaml
cli: 0.3.0                     # exact CLI version this repo is built with
workspace: workspace           # local folder for mcpgp:// references (default for --workspace)
servers:                       # definitions in this repo
  - servers/orders/orders.mcpgp.yaml
  - servers/orders-readonly/orders-readonly.mcpgp.yaml
out: dist
release:
  versionFrom: contract        # contract (default: the API version); TODO: git-tag | file:VERSION | flag
  baseline: last-release       # what `diff` compares against when --against isn't given
sign:
  key: ${env:MCPGP_SIGNING_KEY}  # the key `mcpgp sign` uses (a reference)
```

| Key | Used by |
|---|---|
| `cli` | every command: the version pin |
| `servers` | `validate --all`, `build --all`, `test --all` |
| `workspace` | every command that compiles, when `--workspace` isn't given |
| `out` | `build`, when `--out` isn't given (default `dist`) |
| `sign.key` | `sign`, when `--key` isn't given (default `${env:MCPGP_SIGNING_KEY}`) |
| `release.versionFrom` | only `contract` for now; other values warn and fall back to the contract (**TODO**) |
| `release.baseline` | `diff`, when `--against` isn't given (default `last-release`) |
| `platform` | **TODO**: `mcpgp://` references from a platform instead of `workspace`; warns |

## 3. Commands

| Command | Does |
|---|---|
| `mcpgp init [--from-spec <file\|url>] [--name <n>] [--out <dir>]` | Scaffold a definition project: the definition, the spec under `contracts/`, and `tests/smoke.yaml` with cases for read tools that need no arguments (§3.1) |
| `mcpgp validate <def…\|--all>` | Schema + compiler checks; no output files |
| `mcpgp preview <def> [--tools] [--customer] [--format md\|json]` | What the agent gets (the tools, listed or found by search, what they do, their inputs) and what the customer configures (the example config) (§3.6) |
| `mcpgp explain <def> <tool>` | Provenance of a tool's name, title, description |
| `mcpgp diff <def…\|--all> [--against <last-release\|tag\|ref\|manifest.json\|dir>] [--format text\|md\|json] [--fail-on-breaking]` | What changed since the last release or another build: tools added, removed and renamed, inputs and outputs, annotations, contracts, the server's settings; breaking changes first (§3.5) |
| `mcpgp names accept <def> <tool…\|--all>` | Write the names the policy gives now into `mcp-names.lock`: accepts renames (a breaking change for clients) and locks names the file doesn't have yet; tools by current name, new name or operation id. Existing entries keep their order, and entries of tools not in the build stay |
| `mcpgp build <def…\|--all> [--version <v>] [--build-number <n>] [--source <uri>] [--frozen-lock] [--no-output-schemas] [--manifest-only] [--out <dir>]` | Compile + package: `dist/<name>-<version>.zip` and `dist/<name>-<version>.manifest.json`; `--manifest-only` writes only the manifest; `--no-output-schemas` leaves tools without output schemas whatever `schemas.output` says |
| `mcpgp test <def…\|--all> [--suite tests/]` | Start the server against a mock made from the specs; run a smoke check and the test cases (§3.2). With several definitions each uses its own `tests/` |
| `mcpgp keygen [--out <dir>]` | Create a signing key pair (`mcpgp.key`, secret; `mcpgp.pub`, to publish) |
| `mcpgp sign <dir> [--key <ref>]` | Sign a release directory (§3.4): `SHA256SUMS` over its files and `SHA256SUMS.sig` |
| `mcpgp verify <zip\|dir> --key <pub>` | Check the signature of `SHA256SUMS` and every file against it, in a package or a signed directory; extra or changed files fail |
| `mcpgp migrate <def…\|--all>` | Upgrade definitions to the current `apiVersion`; there is one (`mcpgp/v1`) so far, so it says there's nothing to change, and refuses formats it doesn't know (§3.8) |
| `mcpgp try <def> (--target <url> \| --runtime-config <file> \| --mock) [--tool <name> --args <json>]` | Call a tool as an agent would, with the server running locally: against a real target the developer's machine reaches (never in CI by default), with credentials from a runtime config, or against a mock; without `--tool`, lists the tools (§3.7) |
| `mcpgp push <def> [--platform <url>] [--server <id>] [--message <text>] [--build] [--verify-local] [--release]` | Upload the definition project to a platform as its server's next version; optionally build, compare and release it there (§3.3) |

Global flags: `--config <file>`, `--ignore-version`, `--help`, `--version`; on the commands that
compile: `--workspace`, `--json` (machine-readable output), `--quiet`. `--no-color` (and `NO_COLOR`):
no colour in the output, which has none so far.

### 3.1 `init`

`--from-spec` takes a file or an `https` URL (plain `http` only for localhost) of an OpenAPI 3.x or
Swagger 2.0 document. The API id comes from the spec's title ("Orders API" → `orders`), the server
name adds `-mcp` (or `--name`), the contract version is `info.version`, and instances start in
`single` mode. Existing files are never overwritten. Without `--from-spec`, the definition points at
`contracts/openapi.yaml` for the developer to fill in.

A local spec that refers to other files (`$ref: paths/entries.json`) brings them along: `init` follows
the references from file to file, inside the spec's folder, and copies the files it reached under
`contracts/` with the same layout (unreferenced files in the folder stay behind). A referenced file
that is missing, a reference to a part that isn't there, or a reference to a URL is reported, and
nothing is written. A spec from a URL is copied alone.

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

### 3.4 `sign`

`mcpgp sign dist/` writes `dist/SHA256SUMS` (`<sha256>  <file>` for every file directly in the
directory, sorted) and `dist/SHA256SUMS.sig` (minisign, Ed25519), replacing earlier ones. It signs
what you publish next to the packages; each package is signed inside by `build` when the
definition has `build.sign.key` (a package's signature can't be added afterwards: its docs and files
say whether it is signed). The key is a reference (`--key '${env:NAME}'` or `${file:path}`), never
the key itself. Customers check with `mcpgp verify dist/ --key mcpgp.pub`, or with `minisign -V`.

### 3.5 `diff`

Compares the definition as it is now with an earlier build of it, from their manifests. The earlier
build is `--against` (default `release.baseline`, else `last-release`):

- `last-release`: the latest git tag reachable from the current commit (`git describe --tags`);
- a git tag or commit: the definition is compiled as it was there (a temporary worktree; the
  repository config's workspace, when inside the repository, at that commit too);
- a manifest file (`dist/orders-mcp-1.4.0.manifest.json`), or a folder of them (a release's `dist/`:
  the newest `<server>-*.manifest.json`).

A server that didn't exist then shows everything as new. Tools are matched by their operation (and
content type), so a rename is a rename. **Breaking**: a tool removed or renamed, a contract or
sign-in type no longer offered, an input removed or now required or of another type, a new required
input, an output field removed, the instance mode changed. **Worth a look**: annotations,
confirmation, exposure, the default contract, a support pack removed. **Other**: new tools, optional
inputs, contracts, texts. `--format text` (default), `md` (a pull-request comment) or `json`;
`--fail-on-breaking` exits 6 when anything breaks. The platform shows the same on a server's
Changes page.

### 3.6 `preview`

The tools in the build's exposure mode (listed, or found by search), with their title, read-only
or destructive, and their inputs (`*` required); and the example config the customer starts from.
`--tools` or `--customer` for one of the two; `--format md` (default) or `json`.

### 3.7 `try`

Starts the packaged server locally (stdio) and calls one tool as an agent would. `--target <url>`:
every API's instance at that address, without credentials; `--runtime-config <file>`: the customer's
kind of config, with instances and credentials (secrets as `${env:…}`; the transport is set to stdio);
`--mock`: a mock made from the specs, which also shows the request the server sent. Arguments are a
JSON object (`--args '{"path": {"id": "c-1"}}'`); in multi-instance builds `instance` defaults to the
one instance `--target` and `--mock` configure. Without `--tool` it lists the tools. Exit code 1 when
the call fails.

### 3.8 `migrate`

Reads each definition's `apiVersion`: the current one (`mcpgp/v1`) needs nothing; one this CLI
doesn't know is an error (a newer CLI may know it). Migrations are added with the next format.

## 4. Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Validation or build errors |
| 2 | Usage error (unknown command or option) |
| 3 | Name drift with `--frozen-lock` (a name would change; run `names accept`) |
| 4 | Tests failed (`test`) |
| 5 | Version mismatch (CLI vs `.mcpgp/config.yaml`) |
| 6 | Breaking changes, with `diff --fail-on-breaking` |
| 10+ | Internal error |

## 5. Versions

The server's version **follows the API version**: by default it is the default contract's version
(contract `2.0` → server `2.0.0`). Rebuilding the same contract version (new rules, fixes) adds a build
number as SemVer build metadata: `2.0.0+3`. `release.versionFrom` or `--version` can override this.

`mcpgp diff` (§3.5) lists what changed between builds, breaking changes first.

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
