# Spec: builds, packages, support packs, client registration

Status: **draft v1** · Owner: packager

## 1. Package

One signed archive per build: `<server>-<version>.zip`.

```
orders-mcp-1.4.1.zip
├─ orders-mcp.jar                     if target jar (always for Java)
├─ orders-mcp-1.4.1.mcpb              if target mcpb (desktop bundle, §7.3)
├─ docker/                          if target docker: Dockerfile, docker-compose.yml (§7.2)
├─ source/                          planned: generation.mode codegen or both (not supported yet)
├─ orders-mcp.example.yaml            example runtime config for this build
├─ server.config.schema.json        runtime config schema for this build
├─ docs/                            README · INSTALL · CONFIGURATION · TOOLS · OPERATIONS · CHANGELOG
├─ AGENTS.md · SKILL.md · llms.txt  if build.docs includes agents
├─ manifest.json                    copy of the manifest embedded in the jar, for review and diff
├─ SHA256SUMS · SHA256SUMS.sig
└─ SIGNING-KEY.pub                  the builder's public key, also published by the builder elsewhere
```

How it is built: the CLI carries the runtime as an executable Spring Boot jar and adds the build's
manifest to a copy of it (at `/mcpgp/manifest.json`), so `mcpgp build` needs neither Maven nor the
network. The zip is reproducible: entries sorted, timestamps from `build.createdAt`. Targets and
generation modes this version can't produce yet are reported as warnings.

`server.config.schema.json` narrows the generic runtime-config schema to the build: its transports
and inbound auth modes, `instance` or `instances` (per API in multi-API servers), its contract
versions, its sign-in types, client certificates only with `mtls`, its file forms and packs. Its
`$id` is `urn:mcpgp:config:<server>:<version>`.

**Self-contained rule:** the package runs on the customer's infrastructure and talks only to the
customer's own systems and clients. Nothing in it contacts the platform, the vendor or the internet on its
own. No licence checks, no update checks, no telemetry to the vendor. Any such feature is opt-in and
off by default.

## 2. Builds on the platform

| Action | Rule |
|---|---|
| Download | Whole package or each part. Failed builds offer their log. |
| Archive / restore | Archived builds leave the active list; still downloadable. |
| Delete | Removes files. Record and audit entry stay. **Released builds must be archived first.** |
| Retention | Auto-archive after N days, purge archived files after M days, always keep the last K per variant. Released builds are never purged automatically. |
| Approve & publish | Required for builds started by agents (see platform guardrails). |

## 3. Generated docs

Generated from the manifest, so they always match the build:

| File | Audience | Content |
|---|---|---|
| README.md | people | what it does, tool groups, quickstart |
| INSTALL.md | people | each built format; client registration snippets |
| CONFIGURATION.md | people | every runtime config key from the schema: default, env var, example |
| TOOLS.md | people | tool catalog, availability per contract |
| OPERATIONS.md | people | audit format, telemetry, health, error codes, Collector example |
| CHANGELOG.md | people | from the manifest diff: added, renamed, removed tools |
| AGENTS.md | agents | install, configure, verify, step by step |
| SKILL.md | agents | how to use the tools well |
| llms.txt | agents | index of the docs |

## 4. Support packs

A pack is a descriptor plus an implementation per runtime (or a sidecar).

```yaml
pack: recording
version: 1
group: observability
tools:
  - id: start
    annotations: { readOnlyHint: false }
  - id: stop
  - id: list_calls
    annotations: { readOnlyHint: true }
  - id: list_sessions
    annotations: { readOnlyHint: true }
requires:
  config: { schema: recording.config.schema.json }   # operator config; pack registers only when present
  services: [audit-store]
permissions: [support.recording]
```

- Pack tools go through naming, selection, annotations, audit and redaction like API tools.
- A pack whose `requires.config` is missing at runtime does not register its tools and logs why.
- A pack configured **incompletely** (e.g. issue tracker URL without a token) fails startup with an
  error free of secrets, instead of registering broken tools.
- Initial packs (P0), none of which needs anything outside the server (all implemented): schema (`describe_schema`), audit,
  diagnostics (`tls_certificate_fetch`, connectivity), health, recording, introspection.
- Later (P1), because they need external systems or files: issue tracker (Jira),
  skills, agent feedback.

### 4.1 Pack behaviours

| Pack | Tools | Behaviour |
|---|---|---|
| audit | `audit_list` | Reads the audit file, newest first, paged; filters by tool, instance, outcome, time. |
| recording | `recording_start` (description) · `recording_stop` (findings) · `recording_list` · `recording_sessions` | Named, dated sessions capturing the upstream request/response pairs of **one MCP session** (one running recording per session), bodies included, redacted before storage: secret values, the definition's `redaction.fields` (JSON at any depth, query and form fields), credential and API-key headers; bodies capped at `responseLimitKb`, at most 1000 calls. Stored owner-only under `<storage.root>/recordings/<id>/`; removed after `packs.recording.retainDays` (14) when the server starts. A recording doubles as a configuration capture (its GETs) and a behaviour probe (its writes). |
| diagnostics | `tls_certificate_fetch`, connectivity and auth checks | Read-only; `tls_certificate_fetch` returns PEM chain, subject, issuer, validity, SHA-256 and a hostname warning; limited to configured instance hosts by default. |
| issue tracker | `issue_<area>_create` / `_list` per configured area · `comment_create` / `comment_list` | Areas are configured by the operator with a title, a description and a destination (`local` file store or an issue tracker such as Jira). Area descriptions tell the agent to **list before creating** to avoid duplicates. |
| skills | `skill_get` · `skill_update` · `skill_improve` · `skill_history` · `skill_test_generate` | Confined to configured paths. `skill_improve` **never applies a change**: it returns the current content and asks the agent to propose the change as an issue; `skill_update` only on an explicit user request. Every update is versioned in `skill_history`. |
| introspection | `explain_tool`, `manifest_info` | Build and naming provenance; no network. |
| schema | `describe_schema` | Full or summarized input schemas on demand, drill-down into one definition, `oneOf` variant narrowing (tools-contract §11). Bundled data only; no instance, no network. |

All pack tools that return collections use the paged envelope (tools-contract §10) regardless of the
API's `pagination` option, because their data is local.

A runtime offers only the tools it implements: a pack tool (or built-in) in the manifest that the
runtime version doesn't implement yet is left out of `tools/list` and can't be called; startup logs
which ones.

## 5. Client registration (`<server> client add`)

Per client, an adapter described as data:

| Client | Mechanism | Config location |
|---|---|---|
| Claude Code | `claude mcp add …` (preferred) or project `.mcp.json` | — |
| Claude Desktop | edit `claude_desktop_config.json` (stdio); remote servers via the app's connector settings; or open the `.mcpb` | macOS `~/Library/Application Support/Claude/`, Windows `%APPDATA%\Claude\` |
| Cursor | edit `mcp.json` (`mcpServers`) | `~/.cursor/` or project `.cursor/` |
| VS Code | `code --add-mcp '<json>'` or `.vscode/mcp.json` (`servers`) | — |
| other | `--print` | — |

Rules: merge only this server's entry; back up before writing; `--dry-run` shows the diff; never write
secrets into client configs.

- The entry is named after the server without `-mcp` (`orders`), or `--name`; `--file` writes to
  another file. stdio entries run this jar with this config; HTTP entries use `server.publicUrl`
  (or the local address) plus the base path.
- Claude Code: the command writes the project's `.mcp.json`; `claude mcp add` stays the alternative
  the user can run themselves. Claude Desktop takes only stdio entries in its file; for HTTP servers
  the command explains the connector settings instead.
- Client files must be plain JSON (comments are refused rather than lost).

Snippets: [examples/client/mcp-clients](../../examples/client/mcp-clients).

## 7. Signing and images

### 7.1 Signing

- **Package signature (default):** `SHA256SUMS` lists every file in the package with its SHA-256;
  `SHA256SUMS.sig` is a detached signature made with the builder's **key pair** (Ed25519,
  minisign-compatible format). Verification works fully offline:
  `<server> verify` or `mcpgp verify <zip> --key SIGNING-KEY.pub`.
- The private key comes from a secret reference (`build.sign.key`: `${env:NAME}` or `${file:path}`),
  never from the definition files. It must be unencrypted (`mcpgp keygen`, or `minisign -G -W`); keep
  it in the CI secret store. A build that sets `build.sign` but can't read the key fails; a build
  without `build.sign` produces an unsigned package and says so.
- Signatures are prehashed (algorithm `ED`: Ed25519 over BLAKE2b-512 of `SHA256SUMS`); the trusted
  comment records the build time, the file and the package name.
  Customers should check the public key against one the builder publishes separately (website,
  contract, email), not only the copy in the package.
- **Jar signing (optional):** when `build.jarSigning` is set, the jar is signed with `jarsigner`
  using a company code-signing certificate, so customers that trust the company CA can verify the jar
  on its own.

### 7.2 Container image (`docker` target)

- The package carries what builds and runs the image: `docker/Dockerfile` and
  `docker/docker-compose.yml`. The customer builds it from the package with their own Docker
  (`docker build -f docker/Dockerfile -t <server>:<version> .`), so packaging needs neither a Docker
  daemon nor the network, and the base image comes from wherever the customer's Docker pulls it.
- Default base: **Eclipse Temurin JRE** (`eclipse-temurin:21-jre`); `build.image.base` chooses another
  (e.g. a company-approved or Red Hat UBI image) that has `groupadd` and `useradd`.
- The server runs over HTTP (the build must allow the `http` transport, otherwise the target is left
  out with a warning) on port 8080, as user 10001; `/config` holds `<server>.yaml`, `/data` is
  `storage.root`. The config needs `server.auth` (static keys or OAuth): beyond loopback the server
  refuses to start without client sign-in.
- Not yet: `build.image.push` (to `build.image.registry`) and `build.image.tar` (`docker/image.tar`);
  a build that sets them says so in its warnings. When they come, the image digest is recorded in the
  build report and in `SHA256SUMS`.

### 7.3 Desktop bundle (`mcpb` target)

`<server>-<version>.mcpb` is an [MCP Bundle](https://github.com/modelcontextprotocol/mcpb)
(`manifest_version` `0.3`) that desktop clients install with a click: `manifest.json` and
`server/<server>.jar`. It runs the jar over stdio (`server.type: binary`, `command: java`), so the
computer needs **Java 21 or newer**; a bundled Java runtime may come later. The runtime's
`storage.root` is `~/.<server>`.

- One API and one instance: the install dialog asks for the target server and the sign-in for the
  first type the API supports (basic, bearer, API key or OAuth client credentials; secrets are marked
  `sensitive`, so the client keeps them in the system's key store). Its answers reach the server as
  environment variables (`<PREFIX>INSTANCE__BASEURL`, `…__AUTH__…`, runtime-config §3).
- Several instances or APIs, or mTLS: the dialog asks for the `<server>.yaml` file.
- `tools` lists what `tools/list` shows in the build's exposure mode.

### 7.4 Kubernetes

Not provided for now. Customers deploy the image with their own manifests. Plain manifests or a Helm
chart can be added when a customer asks.

## 6. Security considerations

- Packages are self-contained and never contact the platform or vendor (ADR-0003).
- Checksums are signed; `verify` checks them before install.
- `manifest.json` lists every extension and its checksum.

See [../security.md](../security.md).
