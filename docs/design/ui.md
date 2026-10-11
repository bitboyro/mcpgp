# UI design

Design canvas (private until shared): https://claude.ai/artifact/QbKxr1wh2d85Lotuh71pZU

Pages on the canvas:
- **Final direction** — the chosen design (below).
- **Redesign variants** — A guided setup, B workspace hub, C form/YAML editor, D preview-first;
  plus the delivered package's INSTALL guide and the customer's local admin page.
- **First mock** — the original single-page builder; kept for reference.

## Final direction

**Pattern:** dark sidebar; light controls on the left; a dark **live preview** on the right showing
the consequence of each choice (what the agent gets, what the customer configures, what is shipped).

**Style:** Geist + Geist Mono; neutral greys; one violet accent (`#5B4BEA`); dark stage `#0E0F14`;
generous radii (12–22 px); segmented controls for choices.

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
| Overview (canvas: Shape) | `/servers/:id` | the definition's choices and packs, editable: changes preview live (agent, customer setup, package, checks), Review shows the file's diff, Save makes the next version |
| Tools & rules | `/servers/:id/tools` | filters, search, the tool as the agent gets it, its provenance |
| Connections | `/servers/:id/connections` | contracts, sign-in types, target servers; the shipped example config |
| Sources | `/servers/:id/sources` | versions, files, upload a new version, checks |
| Builds | `/servers/:id/builds`, `/builds` | live log, downloads, package files, release, archive, restore, delete |
| Activity | `/servers/:id/activity`, `/activity` | timeline with filters; approvals |
| Shared library | `/library` | items by kind (adding them is API / CLI) |
| API & agents | `/agents` | identities, tokens (shown once), guardrails, approvals, agent activity |

Not yet: "Describe a change" (proposals from a description), Package & deliver's formats and docs
(`build.targets` isn't in the manifest, so it needs the resolved definition), and editing tools and
rules on the page. The platform MCP server comes with M7.

## Known follow-ups

- ~~Label every target-server list on platform pages as **example** data.~~ The customer-setup
  previews show the package's example config, marked **Example data**.
- Tools page: "Try against the mock" instead of a real test call.
- ~~Add a **Naming** section to Tools & rules (policy, vocabulary, limits, old → new table).~~ Done:
  Tools & rules → Naming.
