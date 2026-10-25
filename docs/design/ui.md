# UI design

Design canvas (private until shared): https://claude.ai/artifact/QbKxr1wh2d85Lotuh71pZU

Pages on the canvas:
- **Final direction** — the chosen design (below).
- **Redesign variants** — A guided setup, B workspace hub, C form/YAML editor, D preview-first;
  plus the delivered package's INSTALL guide and the customer's local admin page.
- **First mock** — the original single-page builder; kept for reference.

## 0.4.0 design boundary (agreed 2026-10-23)

The [release plan](../process/release-0.4.0.md) scopes this pass to readability, calmer visual
hierarchy, polished API/agent utilities, plain-language labels/help, a concise Overview dashboard
with a setup checklist, a baseline instructions editor/preview, existing versioned template
selection/application, and accurate visible release/build metadata. Clarify Builds versus Activity
while retaining build/release audit events. Detailed visual tokens, wording and placement remain
subject to review; these are planned changes, not descriptions of completed UI.

The server navigation was regrouped by configuration group before release (2026-10-25, reopening
the earlier deferral; see [Server navigation](#server-navigation-2026-10-25)). Access control and
Observability pages are required for 0.4.0 and not built yet. Comprehensive advanced forms, a new help/video center, AI description automation,
expanded presets, Tab predictions and a complete branding system are roadmap work. A simple logo
may be included if reviewed and ready, without blocking release. Existing design/implementation
notes below are the baseline until each scoped change is implemented and verified.

## Final direction

**Pattern:** dark sidebar; light controls on the left; a dark **live preview** on the right showing
the consequence of each choice (what the agent gets, what the customer configures, what is shipped).

**Style:** Geist + Geist Mono; neutral greys; one violet accent (`#5B4BEA`) for primary actions, links
and focus; dark stage `#0E0F14`; generous radii (12–22 px); segmented controls for choices.
Type (0.4.0): body 16px / 1.6, labels, hints and tables 14px, card headings 17px, page titles 24px
(`--text-*` tokens in `styles.css`). Page background `#F7F8FA`, dividers `#ECEEF2`. Icons: Lucide
(`lucide-react`, ISC), one name per meaning in `ui.tsx`, in front of the label on main actions; the
mcpgp mark stays our own. The sidebar footer has two labelled buttons: **API reference** (the API and
agents page) and **Connect an agent**.

**Words (0.4.0):** *API definition* is the OpenAPI or Swagger document; *API version* is 1.0, 2.0 (what the
file format calls a contract); *revision* is a saved state of a server ("Save as revision 6"). Library
items keep *version*, because they're referenced as `@1`. *Validate* checks a revision. Labels and
headings use "and", not "&". Help under a setting is one sentence of about 15 words; more detail opens in
place under **How it works** (`Help` in `ui.tsx`), never only on hover.

Page names in this table are the canvas's; the 2026-10-25 regroup renamed and split several of them
(see [Server navigation](#server-navigation-2026-10-25)).

| Page | Left | Right (preview) |
|---|---|---|
| All servers | Server cards with Open / Duplicate / New variant | Shared library, recent builds, or the duplicate/variant panel |
| Shape | "Describe a change" + six plain-language choices | Agent / Customer setup / Package / Changes |
| Tools & rules | Tool list, filters, ordered rules | Selected tool as the agent gets it, with provenance |
| Connections | Contracts shipped, instance mode, mixed versions, sign-in types | What the customer will configure (example data) |
| Sources | Linked source, switch source, checks | Resolved file tree, merge order, digest |
| Package & deliver | Formats, generation mode, HTTP client, docs, packs | Package contents, build log, pipeline command |
| Builds | Builds with Download / Archive / Delete, retention | Selected build, per-format downloads, delete confirmation |
| Activity | Timeline of changes and builds | Selected build, approve & publish |
| API & agents | Identities, agent guardrails | REST API, platform MCP server, agent activity |

## Implementation

`platform/web`: React, Vite, TypeScript, React Router and TanStack Query, the same stack as the other
bitboy.ro web apps; plain CSS with the tokens above (`src/styles.css`); Geist bundled from Fontsource.
Built into the platform jar and served by it (`WebUi`), on the API's own origin.

| Page | Route | State |
|---|---|---|
| Sign in | `/sign-in` | local and directory accounts; company SSO (OIDC) |
| All servers | `/` | cards, duplicate / variant panel, new server (folder or zip), shared library |
| Overview | `/servers/:id` | one card per area (API definitions, Tools, Target APIs, Access control, Execution, Observability, Delivery, General) with its purpose, state and summary, linking to the page; one "Next" step for the most important unfinished area; "Preview as agent" shows the agent, customer setup, package and validation previews. Nothing is edited here |
| General | `/servers/:id/general` | name, title, server version; instructions for agents (`metadata.instructions`) with a live preview of what initialize sends; the latest revision, variant and base |
| API definitions | `/servers/:id/apis` | specs, contracts shipped per API, version detection, upload a new version, saved versions, files, checks |
| Tools | `/servers/:id/tools` | what the agent sees (exposure mode) and support packs; filters, search, the tool as the agent gets it, its provenance; output schemas; Operations view (with what tools may do per API), Naming, Notices |
| Target APIs | `/servers/:id/targets` | one or several target servers and what happens when they run different versions; per API: sign-in types, per-user credential, token exchange; the shipped example config |
| Access control | `/servers/:id/access` | sign-in methods offered and default, scope → tool permissions, sessions default; deployment and administrator settings shown read-only |
| Execution | `/servers/:id/execution` | confirmations, response and request limits, batching, retained results, rate limits; always-on protections |
| Observability | `/servers/:id/observability` | audit file, rotation, retention and arguments; log level; OpenTelemetry on/off, exporter, service name, signals; paths and endpoints read-only; health endpoints |
| Packaging | `/servers/:id/packaging` | how agents connect (stdio, HTTP), formats, docs and contents as they ship |
| Builds and releases | `/servers/:id/builds`, `/builds` | live log, downloads, package files, release, archive, restore, delete |
| Changes | `/servers/:id/changes` | differences between versions |
| Activity | `/servers/:id/activity`, `/activity` | timeline with filters; approvals |
| Shared library | `/library` | items by kind (adding them is API / CLI) |
| API & agents | `/agents` | identities, tokens (shown once), guardrails, approvals, agent activity |

The old server routes `/sources`, `/connections`, `/safety` and `/package` redirect to
`/apis`, `/targets`, `/execution` and `/packaging`, keeping the query string.

Not yet: "Describe a change" (proposals from a description), choosing formats and docs on Packaging
(it shows them; they change in the definition), and editing rules on the page. The platform MCP server comes with M7.

## Known follow-ups

- ~~Label every target-server list on platform pages as **example** data.~~ The customer-setup
  previews show the package's example config, marked **Example data**.
- ~~Tools page: "Try against the mock" instead of a real test call.~~ Done: Try it on a tool.
- ~~Add a **Naming** section to Tools & rules (policy, vocabulary, limits, old → new table).~~ Done:
  Tools → Naming.

## Server navigation (2026-10-25)

Settings are grouped by the question they answer, with names taken from MCP, OpenAPI, OAuth,
OpenTelemetry and common API gateway consoles where one exists. Security is not one page: it spans
Target APIs, Access control, Execution and Observability.

| Sidebar | Page | Configuration group |
|---|---|---|
| — | Overview | status and the definition's choices |
| Configure | General | identity and instructions (MCP `initialize`: server info, instructions) |
| | API definitions | the OpenAPI documents, contract versions, version detection |
| | Tools | what the connecting agent sees: tools, operations, naming, output schemas |
| | Target APIs | the upstream APIs, their addresses and how the server signs in to them |
| | Access control | who may connect and what they may do: client sign-in, scope permissions, sessions; deployment restrictions and policy files read-only |
| | Execution | how permitted calls run: confirmations, limits, batching, retained results |
| | Observability | audit, logs, traces and metrics, health |
| Deliver | Packaging | formats, transports, generated docs and signing |
| | Builds and releases | builds, their files, release approvals |
| History | Changes | differences between versions |
| | Activity | who changed what on the platform; not the delivered server's runtime audit |

Moved in the regroup: contracts shipped and version detection (Connections → API definitions), the
output schema switch (Security & safety → Tools, saved on change as the next version), the
Definition card (Overview → General). The definition key is `upstreamAuth` from 0.4.0
(`downstreamAuth` is read as a deprecated alias with a warning); UI labels say "sign-in" rather than
"upstream"/"downstream". On Access control and Observability the platform edits the package's defaults and ceilings
(`runtimeDefaults`); settings the operator or an administrator supplies (paths, endpoints, secrets,
identity provider, tool restrictions, identity and organization policy files) are listed with their
config key under a "Set when deployed" marker. `logging.target` and the `logs` telemetry signal are
left out because the runtime doesn't act on them.

## MCP server index review (2026-10-23)

Implemented for the live review: the heading/sidebar say **All MCP Servers**; one **New MCP Server**
action opens API definition import or existing-project upload. A short three-step empty state
explains import, tool selection and packaging without a second creation button. The platform MCP server
comes first as a separate full-width utility card, followed by custom MCP servers. Shared Library content
and inheritance explanations no longer occupy a permanent index stage; duplicate/variant guidance
appears only during that action. Closing creation dismisses its panel entirely.

**Connect an agent** opens its existing single setup flow on **API & agents** (`/agents?connect`),
including links from Overview and the legacy index query. The index no longer opens agent setup
automatically. **MCP server** identifies the generated service; **target API** identifies its upstream.
The index/sidebar wording is updated; a complete platform-wide terminology pass remains in scope.

Shared Library (2026-10-24): all categories have versioned starter examples. Templates show Bare,
Default, Basic and Enterprise, sharing their configuration with the import presets. Open inspects
all categories; naming/notices retain editors and the other categories show source. Explain
standards versus conventions and avoid implying notices or Enterprise defaults certify compliance.

Index build affordance (2026-10-24): each compilable MCP server card offers Build and opens the
resulting build progress. The platform utility shows a highlighted question-mark hint, **Build it
to use it**, until the latest definition has a successful build; then it links to the ready package.
Existing API & agents quick-download can also build the standard platform package on demand.
