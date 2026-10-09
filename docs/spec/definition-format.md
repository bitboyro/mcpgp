# Spec: definition format (`*.mcpgp.yaml`)

Status: **draft v1** · Owner: core · Schema: [definition.schema.json](../../schemas/src/main/resources/schemas/definition.schema.json)

The definition is the single source of truth for one MCP server. The web UI, the CLI, the REST API
and agents all read and write it. Builds are reproducible from a definition version plus its lock file.

## 1. Files

A definition is a **project**: one root file plus every file it references.

```
orders/
├─ orders.mcpgp.yaml            root (exactly one per project)
├─ contracts/                 OpenAPI documents; may be multi-file themselves
├─ overlays/                  OpenAPI Overlay 1.0 documents
├─ overrides/                 rule files, merged in file-name order
├─ packs/                     support-pack configuration
├─ skills/                    files for the skills pack
├─ docs/templates/            optional extra sections for generated docs
└─ mcp-names.lock             written by builds, committed
```

See [examples/platform/orders](../../examples/platform/orders) for a complete project.

## 2. Root document

```yaml
apiVersion: mcpgp/v1          # required; older versions are migrated on import
kind: McpServer               # required
metadata:
  name: orders-mcp              # required; [a-z0-9-]{2,63}; unique in the workspace
  title: Example Orders API           # shown to people
  description: …              # becomes the MCP server instructions intro
extends: …                    # optional; makes this a variant (§6)

apis: [ … ]                   # required; one or more (§3). `api:` is accepted for exactly one.
instances: { … }              # §4
naming: { … }                 # see naming.md
overrides: { … }              # see naming.md §5 and §5 below
exposure: { … }
safety: { … }
packs: { … }
runtimeDefaults: { … }        # defaults and limits for the operator's runtime config
generation: { … }
build: { … }
```

### 2.1 Reference

| Key | Type | Default | Notes |
|---|---|---|---|
| `apis[].id` | string | — | `[a-z][a-z0-9_]{1,31}`. Used for tool prefixes and in runtime config. |
| `apis[].title` | string | `id` | |
| `apis[].toolPrefix` | string | `id` | Prepended by the naming pipeline (namespace step). |
| `apis[].contracts[]` | list | — | At least one. |
| `apis[].contracts[].version` | string | — | Quoted, e.g. `"2.0"`. Unique per API. |
| `apis[].contracts[].spec` | path \| URL | — | Swagger 2.0, OAS 3.0 or 3.1. URLs only for allow-listed hosts. |
| `apis[].contracts[].overlays` | list of paths | `[]` | Applied in order. |
| `apis[].contracts[].default` | bool | last listed | Exactly one default per API. |
| `apis[].detect` | object | none | optional: `{ method, path, pattern, onFailure: useDefault \| refuse }`; first capture group is matched against shipped versions (runtime-config §4.4) |
| `apis[].select.access` | enum | `read-write` | `read-only` · `read-write` (no DELETE) · `all` |
| `apis[].select.include` / `exclude` | selector | all | See §5. |
| `apis[].select.deprecated` | enum | `hide` | `hide` · `show` · `mark` |
| `apis[].downstreamAuth.supported` | list | from spec | `basic` · `apiKey` · `bearer` · `oauth2ClientCredentials` · `mtls` · `custom:<id>` |
| `apis[].downstreamAuth.identity` | enum | `service-account` | `per-user` is P2. |
| `apis[].downstreamAuth.unsatisfiedTools` | enum | `hide` | `hide` · `error` |
| `apis[].requestBody` | object | `{ multiple: split }` | operations with several request body types: `split` (one tool per type, suffixes from `suffixes`) or `prefer` (one tool, first match of `prefer`). Default suffixes: `multipart/form-data → multipart`, `application/x-www-form-urlencoded → form`, `application/octet-stream → binary`. See tools-contract §13 |
| `apis[].pagination` | object | none | **optional**; normalizes paging (tools-contract §10). `{ style: offset \| page \| cursor \| link-header \| client, request: {…param names}, response: {items, total, next}, defaultLimit, maxLimit }` |
| `instances.mode` | enum | `single` | `single` · `multi` |
| `instances.mixedContracts` | enum | `union` | `union` · `namespaced` · `forbid` (multi only) |
| `instances.max` | int | none | |
| `exposure.mode` | enum | `plain` | `plain` · `discovery` · `toolsets` · `hybrid` (tools-contract §15) |
| `exposure.discoveryStyle` | enum | `search` | `search` (search → describe → call) · `catalog` (list → schema → invoke); discovery and hybrid only |
| `exposure.toolsets` | object | by tag | toolsets mode: groups of tools, default one per tag; `{ <name>: { match: <selector>, description } }` |
| `exposure.pinned` | list of tool names | `[]` | hybrid only |
| `exposure.plainSearch` | bool | `false` | add `search_tools` + `describe_tool` in plain mode |
| `safety.confirmDestructive` | bool | `true` | safe by default; destructive tools ask for confirmation (tools-contract §8) |
| `safety.responseLimitKb` | int | `256` | operator may lower, not raise |
| `schemas.input` | enum | `full` | `full` · `lean` (tools-contract §11) |
| `files.forms` | list | `[base64, path]` | file-input forms the build supports; the operator enables them at runtime |
| `packs.<id>` | object \| `$ref` | — | presence enables the pack |
| `runtimeDefaults` | object | see [runtime-config.md](runtime-config.md) | values become defaults in the generated config schema; `allowed` lists restrict the operator |
| `resources.uriScheme` | string | `api` | scheme for operations exposed as resources (tools-contract §14.1) |
| `prompts` | list | `[]` | prompt templates (tools-contract §14.2) |
| `extensions` | object | none | custom tools, enrichers, call filters ([extensions.md](extensions.md)) |
| `generation.mode` | enum | `runtime` | `runtime` · `codegen` · `both` |
| `generation.runtime` | semver range | latest | pin the runtime version, e.g. `1.8.x` (see compatibility.md) |
| `generation.client` | object | `{ library: jdk, json: jackson }` | |
| `generation.codegen` | object | — | `{ output: build \| repo, extend: subclass \| hooks \| regions, classes: per-tag \| per-tool }` |
| `build.targets` | list | `[jar]` | `jar` · `mcpb` · `docker` (Kubernetes manifests / Helm: later) |
| `build.image` | object | Temurin JRE | `docker` target: `{ base, registry, push: true\|false, tar: true\|false }` (packaging.md §7) |
| `build.docs` | list | `[people]` | `people` · `agents` |
| `build.sign` | object | `{ key: ${env:MCPGP_SIGNING_KEY} }` | key-pair signature of `SHA256SUMS`; `null` disables (packaging.md §7) |
| `build.jarSigning` | object | none | optional: `{ keystore, alias, password }` with a company code-signing certificate |

## 3. APIs and contracts

- A server has **one or more APIs**. With more than one, tool names are prefixed per API and each
  API has its own contracts, sign-in types and (at runtime) its own instances.
- An API has **one or more contracts**. Each contract is one OpenAPI document version. Formats may
  differ between versions of the same API.
- The build ships **all** contracts. Which one a target server uses is decided at runtime by the
  operator (`auto` detection or a pinned version).
- Tool availability is computed per contract. A tool missing from a contract is reported clearly
  when called against an instance using that contract.

## 4. Instances

The definition only declares the **mode** and policies. Target servers themselves are never part of
a definition: they belong to the operator's runtime config.

- `single`: tools have no `instance` parameter.
- `multi`: every tool has a required `instance` parameter. There is no default instance.
  See [ADR-0004](../decisions/0004-instance-required-parameter.md).

## 5. Selectors and rules

A **selector** matches operations/tools. All given fields must match (AND); list values match any (OR).

```yaml
match:
  api: orders                        # optional in single-API servers
  tag: [customers, accounts]
  method: [POST, PUT]
  path: "/customers/**"      # glob
  operationId: "~^create"          # "~" prefix = regex
  contract: "2.0"
  extension: { x-internal: true }
```

A **rule** is a selector plus changes:

```yaml
- match: { tag: customers }
  description: { prepend: "[Orders] " }
- match: { method: DELETE }
  description: { append: " Irreversible." }
  annotations: { destructiveHint: true }
- match: { path: "/internal/**" }
  hidden: true
- match: { operationId: createCustomer }
  name: { set: customer_create }
  title: { set: Create customer }
  params:
    accountId: { description: { set: "Account the customer belongs to. Use account_list." } }
```

Parameters in `params` are addressed by name, or by `<group>.<name>` (`path.id`, `query.id`) when the
same name exists in several locations; see [tools-contract.md](tools-contract.md) §2.

Optional rule fields:
- `sensitive: true`: marks the tool as sensitive (tools-contract §12).
- `contentType: <media type>`: binds the tool's request content type (tools-contract §13.1).
- `expose: resource`: exposes a `GET` operation as an MCP resource instead of a tool (tools-contract §14.1).
- `accept: [<media type>, …]`: overrides the `Accept` header (tools-contract §13.2).
- `parts.<name>`: describes a multipart part the spec only declares as binary:
  `{ description, contentType, secret: true|false, required }`; `contentType` fills in for specs that
  omit OpenAPI `encoding` (common in Swagger 2.0).

Operations: `set`, `prepend`, `append`, `replace: { pattern, with }`, `template` (Mustache:
`{{operationId}}`, `{{summary}}`, `{{api}}`, `{{contract}}`). Fields: `name`, `title`, `description`,
`params.<name>.description`, `annotations`, `hidden`.

`overrides.include` (glob) loads rule files; `overrides.rules` adds inline rules after them.
Rules apply **in order; later rules win**.

## 6. Variants

```yaml
apiVersion: mcpgp/v1
kind: McpServer
metadata: { name: orders-readonly }
extends: mcpgp://acme/servers/orders-mcp@latest     # or a relative path, or @<version>
apis:
  - id: orders
    select: { access: read-only }
build: { targets: [mcpb] }
```

- The parent is resolved first; the variant is merged on top (§7).
- `@latest` follows the parent; `@<version>` pins it.
- A variant shares the parent's lock file entries unless it renames tools itself.

## 7. Multi-file resolution

| Rule | Detail |
|---|---|
| `$ref: <path>` | Replaces the value with the referenced file's content (YAML or JSON). |
| `include: <glob>` | Loads all matching files, sorted by path, and merges them in order. |
| Relative paths | Resolved from the **referencing** file. |
| Root restriction | No path may resolve outside the project root (`..` escapes, absolute paths and symlinks out are errors). |
| Remote references | Off by default; allowed only for hosts in the workspace allowlist. |
| Cycles | Detected; the error lists the full chain. |
| Workspace references | `mcpgp://<workspace>/<kind>/<name>@<version>` (kinds: `servers`, `contracts`, `naming`, `rules`, `packs`, `templates`) resolve to the shared library. The CLI maps them to local files with `--workspace <dir>`, using `<dir>/mcpgp-workspace.yaml`. They are the only way to reuse files from outside the project. |
| Maps | Deep-merged; later wins. `null` removes an inherited key (e.g. `packs: { jira: null }` in a variant). |
| Lists of objects with a key | **Merged item by item**, matched on the key: `apis` and `instances` by `id`, `contracts` by `version`. An item present only in the later file is added; `$remove: true` removes the inherited item (e.g. `- { version: "1.0", $remove: true }`). |
| Other lists | `overrides.rules` and `include`d rule files **append**; every other list **replaces**. Override with `$merge: append \| replace`. |
| OpenAPI `$ref`s | Resolved relative to the spec file, under the same root restriction. |

## 8. Lock file

`mcp-names.lock` maps a stable operation identity (`api`, `method`, `path`, contract-independent
`operationId`) to the published tool name. Builds write it; it is committed. See [naming.md](naming.md) §7.

## 9. Validation

A definition is valid when it matches the JSON Schema **and** the compiler reports no errors:
duplicate API ids, missing default contract, unresolved files, name collisions, limits exceeded,
selectors that match nothing (warning), rules that match nothing (warning).

## 10. Versioning

`apiVersion` changes only for breaking format changes. Every new `apiVersion` ships with an automatic
migration (`mcpgp migrate`) and a migration note in the changelog.

## 11. Security considerations

- Definitions are trusted input: they control what agents can do. Changes go through review
  ([guides/ci-pipeline.md](../guides/ci-pipeline.md)); publishing changes made by agents needs a person.
- Spec files are part of the reviewed project; remote references only to allow-listed hosts (§7).
- Path resolution never leaves the project root (§7).

See [../security.md](../security.md).
