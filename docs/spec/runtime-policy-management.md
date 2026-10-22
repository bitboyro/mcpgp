# Spec: runtime policy management API

Status: implemented in the current branch · Extends [layered runtime policy](layered-runtime-policy.md).

## Scope and activation

This first management API edits only deployment/API/instance tool policies. General settings,
organization policy, identity grants and session policies remain outside its write scope.

HTTP deployments opt in with `server.management.enabled: true` and a separate named administrator
key list `server.management.keys: [{name, value}]`. Values are secret references or direct environment
variables and must resolve to at least 24 characters. MCP authentication never grants administration.
Activation and administrator-key changes require restart. A regular, writable config file is required
for applying updates; a read-only mount can still be inspected and previewed. Symlink files are not
replaced. With `server.management.port`, `/management/*` is served only on that port; otherwise it
uses the main HTTP listener. Disabled endpoints return 404. Browser Origin requests are forbidden;
there is no CORS or cookie authentication. Expose remote listeners behind TLS as for other HTTP APIs.

## Requests and responses

Every request needs `Authorization: Bearer <administrator-key>`. Responses use JSON and `Cache-Control:
no-store`. POST requires `application/json`, at most 64 KiB, and at most 64 changes. Unknown fields,
duplicate keys, invalid nesting or unsupported policy paths are rejected. Errors contain a stable
code and safe message; neither raw configuration nor secrets are returned.

| Method and path | Behavior |
|---|---|
| GET `/management/policy` | Current source revision, active configuration hash, desired configuration hash, pending restart fields, configured tool policies and per-tool/instance allow/deny explanations |
| POST `/management/policy/preview` | Validate a candidate without writing the config or making upstream calls; return effective policy diff and a short-lived preview id |
| POST `/management/policy/apply` | Revalidate a previously previewed candidate, compare revisions, durably replace the config, publish through the normal reload path and return updated status |

Preview body:

```json
{
  "expectedRevision": "<revision from GET>",
  "changes": [
    {"path": "/tools", "value": {"allow": ["orders_*"]}},
    {"path": "/instances/0/tools", "value": {"deny": ["orders_delete"]}}
  ]
}
```

Supported paths are `/tools`, `/apis/<id>/tools`, `/instance/tools`, `/instances/<index>/tools`, and
corresponding per-API instance paths. Parent scopes must already exist. A value replaces the whole
policy object at that path; `null` removes the local policy and restores inheritance. Array indices
are bound to the source revision, so reordered instances invalidate a preview. Duplicate paths are
rejected. The normal startup schemas and tool-name validator also apply.

Apply body: `{"expectedRevision":"<same revision>","previewId":"<id from preview>"}`. Previews
expire after ten minutes, are bound to the administrator key, are single-use after successful apply,
and are capped at 64 per process. Restart discards them. Apply requires the same base revision and
rechecks the candidate against current environment, flags and source content. If an environment or
CLI policy override masks a requested write, preview/apply returns `POLICY_OVERRIDDEN` instead of
silently applying an ineffective change. No-op source changes are rejected.

Preview shows each changed path, old and new local policy, effective decisions that change, warnings,
and whether serialization will replace YAML formatting/comments. The persisted document is derived
from the unresolved source file: secret references remain references. YAML comments and formatting
are not retained by this first API; JSON is formatted on write. This is disclosed before apply.

## Revisions, persistence and failures

The opaque source revision covers source bytes, desired/active configuration hashes and effective
policy/target decisions. A referenced policy value changing in `.env` therefore invalidates a preview
even when configuration hashes (which hash references rather than resolved secrets) are unchanged. API operations and file reloads share a lock. A changed file, effective override,
or active revision invalidates a preview, even if the administrator submits an older preview id.
Revisions coordinate administrators within this process; simultaneous editing by unmanaged file
writers must be avoided. Source bytes are checked again immediately before atomic replacement.

Serialize to an owner-only temporary file in the same directory, preserve the original file's
permissions, fsync the candidate, recheck the source, then atomically replace and fsync the directory.
Prepare runtime construction before persistence; a failure before replacement leaves disk and the
active snapshot unchanged. Do not fall back to a non-atomic write. A restart recovers the durably
accepted source config. Requests already admitted finish under the previous snapshot. A directory
sync failure after replacement must be reported as persisted with durability uncertain, not as an
unchanged-file failure. Config file watching subsequently reads the same source, rather than a
second competing configuration store.

This API cannot create pending restart changes. Status reports restart-required differences between
the currently desired source configuration and startup-bound settings; these are not applied by a
policy edit. Active/desired hashes identify loaded configurations, while `restartRequired` identifies
settings whose listeners/auth/storage still need restart.

## Authorization, audit and errors

Administrator keys are independent of MCP keys, compared using constant-time SHA-256 digest
comparisons, and never logged or returned. Enabling this API requires file audit logging. Audit each
authorized read, preview and apply (including rejections), plus refused authentication/origin requests.
Use `config.management` events with action, actor, outcome, accepted old/new revisions and changed
paths when available, and
config hash, without request bodies or key values. Apply also records the normal `config.reload`
event identifying the administrator and management origin.

| HTTP | Codes |
|---|---|
| 400 | `MANAGEMENT_REQUEST_INVALID` |
| 401 | `MANAGEMENT_UNAUTHORIZED` |
| 403 | `MANAGEMENT_ORIGIN_FORBIDDEN` |
| 409 | `CONFIG_REVISION_CONFLICT`, `POLICY_OVERRIDDEN`, `PREVIEW_EXPIRED`, `POLICY_NO_CHANGE` |
| 413 | `MANAGEMENT_REQUEST_TOO_LARGE` |
| 415 | `MANAGEMENT_CONTENT_TYPE` |
| 422 | `CONFIG_INVALID` |
| 503 | `CONFIG_STORAGE_UNAVAILABLE`, `CONFIG_SOURCE_UNAVAILABLE`, `CONFIG_UPDATE_UNAVAILABLE` |
| 405 | `MANAGEMENT_REQUEST_INVALID` (unsupported method) |

Durability warnings are returned on a successful apply after replacement rather than inviting a
blind retry of an already-applied revision. If SDK catalog refresh fails after policy publication,
apply likewise returns a persisted/active result with a catalog-refresh warning; operators should
inspect status and restart as needed. Execution uses the published snapshot throughout.
