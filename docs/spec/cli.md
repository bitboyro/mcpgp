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
| `mcpgp init [--from-spec <file\|url>]` | Scaffold a definition project (and `.mcpgp/config.yaml`) |
| `mcpgp validate [<def>…]` | Schema + compiler checks; no output files |
| `mcpgp preview <def> [--tools] [--customer] [--format md\|json]` | What the agent gets / what the customer configures |
| `mcpgp explain <def> <tool>` | Provenance of a tool's name, title, description |
| `mcpgp diff <def> [--against <tag\|manifest.json\|last-release>] [--format md\|json]` | Added, removed and changed tools, schemas and names (informational) |
| `mcpgp names accept <def> <tool…\|--all>` | Accept renames into `mcp-names.lock` |
| `mcpgp build <def…\|--all> [--version <v>] [--build-number <n>] [--source <uri>] [--frozen-lock] [--manifest-only] [--out <dir>]` | Compile + package; `--manifest-only` writes only `dist/<name>-<version>.manifest.json` |
| `mcpgp test <def> [--suite tests/]` | Start the built server against a mock generated from the specs; run smoke + user test cases |
| `mcpgp sign <dist>` / `verify <zip>` | Sign checksums / verify a package |
| `mcpgp migrate <def>` | Upgrade a definition to the current `apiVersion` |
| `mcpgp try <def> --target <url>` | Run the server locally against a real target the developer's machine can reach (never in CI by default) |
| `mcpgp push <dist> [--platform <url>]` | Optional: register the build on a platform workspace |

Global flags: `--config`, `--workspace`, `--json` (machine-readable output), `--quiet`, `--no-color`.

## 4. Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Validation or build errors |
| 2 | Usage error (unknown command or option) |
| 3 | Name drift with `--frozen-lock` (a name would change; run `names accept`) |
| 4 | Tests failed |
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
