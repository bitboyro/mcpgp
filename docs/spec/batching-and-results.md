# Batch execution and retained results

Batch execution, result delivery, and discovery are independently configured core capabilities.
They work with plain, discovery (both styles), hybrid, and toolsets exposure. Support tools are
listed directly and follow normal naming conventions. Defaults preserve existing behavior.
The platform UI exposes these settings under **Security & safety**; saving produces a new definition version.

```yaml
batch:
  enabled: true             # default false; registers batch_call
  maxCalls: 20               # 1–100
  concurrency: 4            # 1–16 concurrent reads
  allowWrites: false        # opt in to sequential writes
  deduplicateRequests: false # opt in to durable requestId receipts
results:
  mode: auto                # inline (default), auto, reference
  captureLimitKb: 4096       # 1–65536; maximum downloaded body
  ttlSeconds: 900            # 1–86400
  maxStoredKb: 65536         # 1–262144; shared serialized-response storage budget
safety:
  responseLimitKb: 256       # inline output budget
```

## Batch calls

```json
{
  "calls": [
    {"id":"customer","tool":"orders_customer_get","arguments":{"instance":"prod","path":{"id":"c-1"}}},
    {"id":"accounts","tool":"orders_account_list","arguments":{"instance":"prod"}}
  ],
  "execution": "parallel"
}
```

`execution` defaults to `sequential`, stopping on the first unsuccessful entry. `parallel` allows
read-only tools only, with bounded concurrency and existing per-instance rate limits. Results remain
in input order. Each entry has an id, state, result and optional upstream metadata. States are
`succeeded`, `failed`, `not_started`, and `outcome_unknown`. The last is conservative: a write with
a transport failure or cancellation may already have happened. An upstream 202 means acceptance,
not completion of its business process; the HTTP status is preserved in metadata. A successful HTTP
response whose output cannot be decoded or captured remains `succeeded` with `outputError: true`;
sequential execution stops so the caller can inspect the issue without repeating a successful write.

All entries are checked before any operation is sent: unique ids (at most 128 characters), tool
availability, toolsets, instance access, selected contract, input schema, request encoding and
credential availability. HTTP OAuth scopes are checked for every target, including namespaced
support-tool names. Contract detection may perform its normal read-only version checks.
Only API tools may be batched; recursive batches and pack calls are rejected.

When `deduplicateRequests` is enabled, a caller may add a `requestId` to a batch. The server writes a
durable receipt before dispatching any operation, bound to that caller and to the canonical batch
(calls and execution policy, excluding its confirmation token). Retrying the same request id and
batch after a lost response returns the recorded outcome with `deduplicated: true`, without calling
the target again. Reusing an id with different arguments returns `BATCH_REQUEST_ID_REUSE`. If a
server stops after recording the start but before recording the result, it returns
`BATCH_OUTCOME_UNKNOWN`; the caller must inspect the target rather than replaying writes. Receipts
survive process restarts under `storage.root/batches`. They retain the batch outcome only; the
separate in-memory `result_get` snapshots retain their normal TTL and restart behavior.

When any entry needs confirmation, one confirmation binds the exact ordered batch and execution
policy to the MCP session. Supply its `confirm` token at the batch level; individual entry tokens
are rejected. Approved entries still pass normal call filters, validation, auth, audit and rate
limits. POST and PUT are supported with `allowWrites: true` and sequential execution. No automatic
retries, transaction, rollback, dependencies, or exactly-once guarantee are provided. Repeating a
batch repeats its operations; clients must not replay writes to recover a response.

An aggregate over the inline budget is retained when result retention is enabled. With retention
disabled, outputs are omitted and compact per-entry states (or counts for an oversized manifest)
are returned. Enable retention when full aggregate inspection is required.

## Retained responses

`inline` preserves existing truncation. `auto` keeps small responses inline and retains large
responses. `reference` retains every successful response. Both register `result_get`. These modes
apply to direct calls, calls through discovery, and batch calls.

A retained response returns `resultId`, `complete: true`, `expiresInSeconds`, and a deterministic
summary (stored bytes, returned item count where available, and bounded field names). Metadata
keeps upstream status and instance information. Tools with an output schema expose an additional
reference/incomplete-result alternative in their served output schema.

```json
{"resultId":"r_...","pointer":"/items","offset":0,"limit":25}
```

`result_get` reads an immutable snapshot without contacting the target. JSON pointers select a
field or array; arrays return whole items within the inline byte budget, `hasMore`, and
`nextOffset`. String selections return bounded text slices. Large objects or individual items must
be narrowed with a deeper pointer. `limit` is 1–200 and defaults to 25. Complete means the complete
response to one request, not all upstream pages.

Snapshots are redacted, session/client bound, held in process memory, and expire after their TTL.
They disappear on restart or reload. Unknown, expired and unauthorized ids all return
`RESULT_NOT_FOUND`. Expired entries are reclaimed on access; live entries are not evicted to make
room. A full store returns `retained: false`, `complete: false`, and `RESULT_STORAGE_FULL` without
replaying the operation. Capture-limit overflow never returns a purported full-result id: it
returns an incomplete notice or `RESPONSE_TOO_LARGE` for a response that cannot be parsed. Writes
may already have succeeded in either case. Error responses retain existing error-detail behavior.

There is no asynchronous job scheduler, arbitrary query execution, caller-controlled increase to
operator limits, or upstream pagination aggregation in this version.
