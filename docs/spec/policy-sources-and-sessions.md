# Policy sources, settings and caller sessions

Status: implementation contract · ADR 0017.

Organization policy is delivered by an administrator-owned mounted YAML/JSON file selected at boot
with `--organization-policy <path>` (or process `MCPGP_ORGANIZATION_POLICY`). Identity policy similarly
uses `--identity-policy` / `MCPGP_IDENTITY_POLICY`. These are trusted process inputs, never runtime
configuration fields or management-editable paths. When selected, files are required at startup.
Reload validates both files; missing/invalid files preserve the complete last accepted snapshot.
Files are watched alongside config. Mount replacement/rotation is supported. There is no remote
fetch or fallback that drops restrictions. OS file permissions and process deployment own trust.

Organization files contain version 1, an operator revision, tool policy, defaults and constraints.
Defaults precede deployment config/env/flags; constraints apply after all overrides. Supported
constraints are read-only instances, required audit, minimum audit retention, maximum upstream
timeout/concurrency/rate, and allowed transport/auth-mode lists. CLI overrides cannot bypass them.
Unknown settings or constraint fields fail validation. Root/API `settings` supply timeout, read-only
and rate-limit defaults inherited by instances; instance fields override defaults within constraints.
A field registry defines writable paths, location, live/restart behavior and type. Management may
edit registered nonsensitive ordinary settings as well as tool policies; no source-file path,
credential, organization/identity document or administrator key can be changed through that API.
Listener/auth-mode/storage changes persist as desired state but retain startup bindings until restart.

Identity files contain version 1, optional default restriction, subject restrictions, and scope
restrictions. Subject keys are `static:<key name>`, `oauth:<verified subject>`, `anonymous` or `stdio`.
Defaults and subject restrictions intersect with organization/deployment/API/instance policies.
Scope grants union within an identity, while explicit denials from any granted scope win. Existing
OAuth scope grants also apply. Each restriction has `tools` (allow/deny/readOnly) and optional
`instances` matching `<api>/<instance>` names with `*`. Missing selections inherit; empty allow or
instances permits none. Tool/instance exact names are validated; unknown globs warn. Separate policy
files may use fleet-wide globs to apply across different builds. Anonymous/static/stdin preserve
existing behavior when no identity file is supplied.

`sessions.enabled: true` exposes a built-in `session_policy` tool. `get` reports only the caller's
restrictions; `restrict` adds another intersection of tools/instances. Subsequent restrictions
cannot widen previous ones; they end with the connection. Built-in control methods remain usable.
HTTP sessions are bound to authenticated principal and cannot be reused by another principal.
Restrictions are stored for the lifetime of a session, never evicted to silently widen it. New
session restrictions are refused at a bounded capacity instead. HTTP DELETE/stdin shutdown cleans
session state. Listings, resource reads, discovery, toolsets, extension API dispatch and call
execution use the same captured caller snapshot. Revocations affect new calls, while admitted calls
finish with their snapshot. Session changes notify tool-list changes. Cursor fingerprints include
caller policy state, so a cursor cannot cross identities or revisions.
