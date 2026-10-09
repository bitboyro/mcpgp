# Spec: naming

Status: **draft v1** · Owner: core

Tool names are part of the contract with agents: prompts, skills and cached tool lists depend on them.
Naming is therefore deterministic, explainable and stable.

## 1. Pipeline

Every tool name goes through the same steps, in this order:

```
source ─► normalize ─► shape ─► vocabulary ─► rules ─► explicit ─► namespace ─► limits ─► collisions ─► lock
```

| # | Step | Input → output | Configured by |
|---|---|---|---|
| 1 | **Source** | operation → raw string | `naming.source`, `naming.fallback` |
| 2 | **Normalize** | raw → lowercase words | `naming.normalize` |
| 3 | **Shape** | words → ordered, cased name | `naming.shape` |
| 4 | **Vocabulary** | glossary substitutions | `naming.vocabulary` |
| 5 | **Rules** | override rules on `name` | `overrides` (definition-format §5) |
| 6 | **Explicit** | per-tool `name: { set }` wins | `overrides` |
| 7 | **Namespace** | add prefixes | `naming.namespace`, `apis[].toolPrefix` |
| 8 | **Limits** | enforce charset and length | `naming.limits` |
| 9 | **Collisions** | resolve or fail | `naming.collisions` |
| 10 | **Lock** | keep published names stable | `mcp-names.lock` |

Worked example, operation `GET /customers/{id}`, operationId `getCustomerAccountByIdV2`:

| After step | Value |
|---|---|
| 1 source | `getCustomerAccountByIdV2` |
| 2 normalize | `get customer account` (dropped `by`, `id`, `v2`) |
| 3 shape (`object_verb`, snake) | `customer_account_get` |
| 4 vocabulary (`customer_account → customer`) | `customer_get` |
| 7 namespace (`orders`) | `orders_customer_get` |
| 8 limits (strict) | `orders_customer_get` |

## 2. Configuration

```yaml
naming:
  extends: mcpgp://acme/naming/acme-standard@2   # optional shared policy
  source: operationId        # operationId | methodPath | tagSummary
  fallback: methodPath       # when operationId is missing
  normalize:
    split: [camel, pascal, kebab, dot, slash]
    dropWords: [by, id, api, v1, v2, v3]
  shape:
    order: object_verb       # object_verb (customer_get) | verb_object (get_customer)
    case: snake              # snake | camel | kebab
    number: singular         # singular | keep
  vocabulary:                # left side may be a regex alternation
    customer_account: customer
    "retrieve|fetch|read": get
    remove: delete
  namespace:
    server: ""               # extra prefix for every tool, rarely needed
    perApi: true             # use apis[].toolPrefix (always true with several APIs)
  limits:
    profile: strict          # strict: [a-z0-9_], ≤ 64   ·   mcp: [A-Za-z0-9_.-], ≤ 128
    onTooLong: shorten-then-hash
  collisions: error          # error | suffix-number | suffix-contract
```

Defaults when a key is not set: `source: operationId`, `fallback: methodPath`, `split` as above,
no `dropWords`, `order: object_verb`, `case: snake`, `number: singular`, no vocabulary, `server: ""`,
`perApi: true`, `profile: strict`, `onTooLong: shorten-then-hash`, `collisions: error`.

## 3. Normalization details

- Splitting: `getCustomerAccountByIdV2` → `get Customer Account By Id V2`; `customer-account.get` → `customer account get`.
- Lowercase all words; drop `dropWords`; collapse repeats.
- HTTP verb inference when the source has no verb (`methodPath`): GET on a collection → `list`,
  GET on an item → `get`, POST → `create`, PUT/PATCH → `update`, DELETE → `delete`.

## 4. Limits

| Profile | Characters | Max length | Use when |
|---|---|---|---|
| `strict` (default) | `a-z 0-9 _` | 64 | Safe for every MCP client and LLM provider. |
| `mcp` | `A-Z a-z 0-9 _ . -` | 128 | Only clients that follow the MCP naming guidance exactly. |

Too long: first apply vocabulary abbreviations, then truncate and append `_` + 4 hex chars of a stable hash.

## 4.1 Content-type suffixes

When an operation is split into one tool per request content type (tools-contract §13.1), the JSON
tool keeps the name produced so far and each other tool gets `_<suffix>` from `apis[].requestBody.suffixes`
(e.g. `orders_customer_create_multipart`). The suffix is added before limits and collisions, so it is
covered by the length check and the lock file.

## 5. Titles and descriptions

Titles and descriptions use only steps 5 and 6 (rules, explicit). They are not locked; changing them
is not breaking. Defaults: title = OpenAPI `summary`; description = `description` (CommonMark kept,
trimmed to 1024 chars with a warning).

## 6. Collisions

Two tools with the same final name:
- `error` (default): build fails, listing both operations and the steps that produced the name.
- `suffix-number`: `_2`, `_3` in operation order. Discouraged; names then depend on order.
- `suffix-contract`: `_v10` for the older contract's tool. Useful with `mixedContracts: namespaced`.

## 7. Stability (lock file)

```yaml
# mcp-names.lock (generated, committed)
version: 1
tools:
  - id: orders:POST:/customers
    name: orders_customer_create
    since: 1.2.0
  - id: orders:GET:/customers/{id}
    name: orders_customer_get
    since: 1.0.0
```

- Once released, a tool keeps its name even if the policy or the spec would now produce another.
  The build reports `name drift: would be X, kept Y`.
- To rename on purpose: `mcpgp names accept <tool|--all>`.
- Optional aliases (P2): the old name keeps working for N releases, with "deprecated, use X" in its
  description.

## 8. Lint (warnings)

- Verb not in the vocabulary's verb list.
- Two names differing only by plural, or by edit distance ≤ 2.
- Name longer than 40 characters.
- Rules that match nothing.

## 9. Provenance

Each name in the manifest records every step that changed it, with file and line, e.g.

```json
"name": "orders_customer_create",
"nameProvenance": [
  { "step": "source", "value": "createCustomer" },
  { "step": "vocabulary", "file": "naming/acme-standard.yaml", "line": 9, "value": "customer_create" },
  { "step": "explicit", "file": "overrides/10-customers.yaml", "line": 6, "value": "customer_create" },
  { "step": "namespace", "value": "orders_customer_create" },
  { "step": "lock", "file": "mcp-names.lock", "since": "1.2.0" }
]
```
