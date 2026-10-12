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

**Other kinds of tools.** A selector without `kind` selects API operations only. `kind` (`api`, `pack`,
`builtin`, `custom`, one or a list) widens it, and `tool` (names, or `~regex`) picks pack, built-in and
custom tools by name: `{ kind: [api, pack, builtin, custom] }` is every tool. For tools that aren't API
tools, rules change `title` and `description` only, and their selector must not use operation keys.

**Long descriptions.** Descriptions are at most 1024 characters. When the result is longer, the spec's
text is shortened (ending in `…`) and the rules are applied again, so what rules prepend or append
stays whole (`DESCRIPTION_TRUNCATED` warns). Only a description that is still too long is cut at the
end.

**Rule ids.** A rule may have an `id` (`[A-Za-z0-9:._-]`). Rules with ids are found and changed by id,
and lists of them merge by id when a variant extends a definition. The platform's Tools page writes
its per-tool changes as inline rules with ids `ui:<operationId>` (or `ui:<tool>`).

### 5.1 Notices

Text added to tool descriptions, for company policy or disclaimers:

```yaml
descriptions:
  notices:
    - id: data-policy
      text: "Company policy: customer data stays in the EU."     # at most 500 characters
      position: append                                          # append (default) | prepend
      match: { tag: [customers] }                               # optional: without it, every tool
    - id: writes
      text: "Changes need a ticket number in the reason."
      match: { method: [POST, PUT, PATCH, DELETE] }
```

- A notice goes on every tool it selects, of every kind (API, pack, built-in, custom); without
  `match`, on every tool. `match` is a selector (§5); `tool` matches final tool names, so notices can
  name any tool.
- Notices are applied after rules and are never cut: a description that would be too long with them
  is shortened to make room. Several notices on one tool keep their order, separated by blank lines.
- They show in a tool's provenance as `notice` steps; `NOTICE_MATCHES_NOTHING` warns about a notice
  that selects no tool. A variant inherits its parent's notices (merged by `id`).

**Shared notices.** Text that many servers carry (a company policy) lives once, in a set of notices:
a library item of kind `notices` on the platform, a file mapped under `notices:` in
`mcpgp-workspace.yaml` for the CLI ([notices.schema.json](../../schemas/src/main/resources/schemas/notices.schema.json)):

```yaml
# mcpgp://acme/notices/company-policy@2
title: Company policy
notices:
  - id: data-policy                     # required in a set
    text: "Company policy: customer data stays in the EU."
  - id: writes
    text: "Changes need a ticket number in the reason."
    match: { method: [POST, PUT, PATCH, DELETE] }
```

A definition includes sets with `descriptions.include` (one reference or a list; a file of the
project works too):

```yaml
descriptions:
  include: [mcpgp://acme/notices/company-policy@2]
  notices:
    - id: writes                        # replaces the set's notice with this id, in its place
      text: "Changes need an approved change request."
      match: { method: [POST, PUT, PATCH, DELETE] }
```

- The sets' notices come first, in the order of `include` (a later set's notice replaces an earlier
  one with the same id), then the definition's own notices.
- Pin a version (`@2`) to keep builds reproducible; a new version of the set reaches the server when
  its definition moves to it (the platform does that for many servers at once, platform-api.md §2.3).
- A shared notice's provenance step names its set (`from: mcpgp://acme/notices/company-policy@2`);
  `mcpgp explain` shows it. A set that doesn't match its schema fails with `NOTICES_INVALID`.
- A variant gets the notices of the definition it extends, shared ones included.

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
| Workspace references | `mcpgp://<workspace>/<kind>/<name>@<version>` (kinds: `servers`, `contracts`, `naming`, `rules`, `notices`, `packs`, `templates`) resolve to the shared library. The CLI maps them to local files with `--workspace <dir>`, using `<dir>/mcpgp-workspace.yaml`. They are the only way to reuse files from outside the project. |
| Maps | Deep-merged; later wins. `null` removes an inherited key (e.g. `packs: { jira: null }` in a variant). |
| Lists of objects with a key | **Merged item by item**, matched on the key: `apis` and `instances` by `id`, `contracts` by `version`. An item present only in the later file is added; `$remove: true` removes the inherited item (e.g. `- { version: "1.0", $remove: true }`). |
| Other lists | `overrides.rules` and `include`d rule files **append**; every other list **replaces**. Override with `$merge: append \| replace`. |
| OpenAPI `$ref`s | Resolved relative to the spec file, under the same root restriction. |
| OpenAPI schemas in other files | Become shared schemas (`components/schemas`), named after the entry that points to their file or after the file (`complete-tls-certificate.json` → `CompleteTlsCertificate`): each file is read once however many places use it, and a schema that refers back to itself (a tree, a chain) isn't a cycle error. Other `$ref`s to files (path items, parameters, responses) are inlined. |

## 8. Lock file

`mcp-names.lock` maps a stable operation identity (`api`, `method`, `path`, contract-independent
`operationId`) to the published tool name. Builds write it; it is committed. See [naming.md](naming.md) §7.

## 9. Validation and diagnostic codes

A definition is valid when every file matches its JSON Schema **and** the compiler reports no errors.
Diagnostics carry a stable code, the file (relative to the project root) and a JSON pointer.

| Code | Level | When |
|---|---|---|
| `DEFINITION_INVALID` | error | the root definition (or the merged result) doesn't match the schema; a contract has no spec |
| `RULES_INVALID` | error | a rule file doesn't match the schema |
| `NAMING_POLICY_INVALID` | error | a naming policy file doesn't match the schema |
| `WORKSPACE_INVALID` | error | the workspace mapping file doesn't match the schema |
| `FILE_NOT_FOUND` | error | a referenced file doesn't exist |
| `FILE_UNREADABLE` | error | a file can't be parsed |
| `PATH_OUTSIDE_ROOT` | error | a path is absolute, or leaves the project root (directly or through a link) |
| `REMOTE_REFERENCE_NOT_ALLOWED` | error | a reference is an `http(s)` URL |
| `WORKSPACE_REFERENCE_UNRESOLVED` | error | an `mcpgp://` reference isn't mapped |
| `REFERENCE_CYCLE` | error | `$ref`, `extends` or naming `extends` loop back; the message shows the chain |
| `DUPLICATE_KEY` | error | two APIs in one file share an `id` |
| `DEFAULT_CONTRACT_AMBIGUOUS` | error | more than one contract of an API is marked `default` |
| `INCLUDE_MATCHES_NOTHING` | warning | an `include` pattern matches no file |
| `MERGE_TYPE_CONFLICT` | warning | a later file replaces a map with a list (or similar) |
| `SPEC_INVALID` | error | a contract is not a Swagger 2.0 / OpenAPI 3.x document, or is malformed |
| `SPEC_VERSION_UNSUPPORTED` | error | OpenAPI 3.2 (planned) |
| `REF_UNRESOLVED` | error | a `$ref` inside a contract points nowhere |
| `OVERLAY_INVALID` | error | an overlay is not Overlay 1.x, or an action has no target |
| `OVERLAY_TARGET_MATCHES_NOTHING` | warning | an overlay action's target selects nothing |
| `OPERATION_ID_DUPLICATE` | warning | two operations in one contract share an `operationId` |
| `UNSUPPORTED_FEATURE` | info | webhooks, callbacks, links and similar are ignored (once per feature) |
| `OPERATION_EXCLUDED` | info | an operation becomes no tool, with the reason (access, include/exclude, deprecated, hidden) |
| `RULE_MATCHES_NOTHING` | warning | a rule matches no tool |
| `NOTICE_MATCHES_NOTHING` | warning | a notice (`descriptions.notices`) applies to no tool |
| `NOTICES_INVALID` | error | a set of shared notices (`descriptions.include`) doesn't match its schema |
| `CONTENT_TYPE_UNKNOWN` | error | a rule binds a content type the operation doesn't accept |
| `RESOURCE_NOT_GET` | error | `expose: resource` on an operation that isn't a `GET` (tools-contract §14.1) |
| `PROMPT_UNKNOWN_ARGUMENT` | error | a prompt message uses `{{name}}` for an argument the prompt doesn't declare (tools-contract §14.2) |
| `RESOURCE_NEEDS_HEADER` | error | `expose: resource` on an operation with a required header or cookie parameter, which a resource URI can't carry |
| `DESCRIPTION_TRUNCATED` | warning | a description was shortened to 1024 characters |
| `NAME_TOO_LONG` | error | a name exceeds the limit and `onTooLong: error` |
| `NAME_COLLISION` | error | two tools get the same name and `collisions: error` |
| `NAME_DRIFT` | warning | a name would change; the locked name is kept |
| `NAME_DRIFT_FROZEN` | error | the same with `--frozen-lock` (CLI exit code 3) |
| `LOCK_INVALID` | error | `mcp-names.lock` doesn't match its schema |
| `PINNED_TOOL_UNKNOWN` | error | in `hybrid` mode, `exposure.pinned` names a tool the build doesn't produce (ignored in other modes) |
| `PACK_UNKNOWN` | warning | a pack isn't available in this version; it is left out of the build |
| `EXTENSION_INVALID` | error | an extension can't be built: a missing or non-jar artifact, a handler that isn't a Java class name, a custom tool's input schema that isn't an object schema, or extensions declared without an artifact |
| `MANIFEST_INVALID` | error | internal: the produced manifest doesn't match its schema (a compiler bug; please report it) |

## 10. Versioning

`apiVersion` changes only for breaking format changes. Every new `apiVersion` ships with an automatic
migration (`mcpgp migrate`, TODO: built with the first v2) and a migration note in the changelog.

## 11. Security considerations

- Definitions are trusted input: they control what agents can do. Changes go through review
  ([guides/ci-pipeline.md](../guides/ci-pipeline.md)); publishing changes made by agents needs a person.
- Spec files are part of the reviewed project; remote references only to allow-listed hosts (§7).
- Path resolution never leaves the project root (§7).

See [../security.md](../security.md).
