# Spec: extensions (custom code)

Status: **draft v1** · Owner: runtime · Applies to the Java runtime; other runtimes provide the same
three extension kinds in their own language.

Most servers need no code. When they do, three extension kinds are available. All of them are
**declared in the definition** and built into the package, so they appear in the manifest, the docs
and the diff like everything else.

| Kind | Does | Can block a call? |
|---|---|---|
| **Custom tool** | A tool whose behaviour is code, not an API operation | — |
| **Request enricher** | Changes the outgoing HTTP request (headers, query) | no |
| **Call filter** | Runs around a tool call: inspect or change arguments and results, or stop the call | yes |

## 1. Declaring extensions

```yaml
extensions:
  artifacts:
    - path: extensions/acme-orders-ext-1.2.0.jar      # inside the definition project, checksummed
  customTools:
    - name: customer_health_summary                  # goes through the naming pipeline
      title: Customer health summary
      description: Summarizes open orders, overdue invoices and recent refunds for one customer.
      inputSchema: { $ref: schemas/customer-health-input.json }
      annotations: { readOnlyHint: true }
      handler: com.acme.orders.CustomerHealthTool
  enrichers:
    - handler: com.acme.orders.TenantHeaderEnricher
      order: 100
  callFilters:
    - handler: com.acme.orders.BlockBulkDeleteFilter
      order: 100
      match: { method: DELETE }                      # optional selector; default: all tools
```

- Name, title, description, input schema and annotations of custom tools live in the definition, not
  in code, so they are reviewed, diffed and documented like API tools.
- Artifacts are part of the definition project (root restriction applies) and their checksums are
  recorded in the manifest.

## 2. Java interfaces

```java
public interface CustomTool {
    ToolResult call(ToolContext ctx, JsonNode arguments) throws Exception;
}

public interface RequestEnricher {
    void enrich(ToolContext ctx, OutgoingRequest request);   // headers, query parameters only
}

public interface CallFilter {
    ToolResult filter(ToolContext ctx, JsonNode arguments, Chain chain) throws Exception;
    interface Chain { ToolResult proceed(JsonNode arguments) throws Exception; }
}
```

`ToolContext` gives read access to: tool name, instance id, contract version, correlation id, the
caller's identity (inbound auth), a logger that redacts secrets, and — for custom tools only — an
`ApiClient` that calls operations of the same instance through the normal pipeline (auth, TLS,
audit). It gives **no** access to credentials or the raw runtime config.

## 3. Order and failures

```
call ─► call filters (by order) ─► [ validation ─► confirmation ─► enrichers (by order) ─► HTTP ] ─► call filters (unwind) ─► result
```

- Lower `order` runs first; equal orders run in declaration order.
- **Enrichers** must be cheap and must not fail the call: an exception is logged and audited, the
  enricher is skipped, the call proceeds.
- **Call filters** may stop a call by returning a result without calling `proceed` (e.g. an error
  result). An exception in a filter → `INTERNAL` result, audited.
- **Custom tools**: an exception → `INTERNAL` result with a generic message; details only in logs and
  audit.
- Extensions never see or change: confirmation tokens, credentials, the `instance` enum, audit events.
- Timeouts: custom tools and filters are bounded by the tool's timeout.

## 4. Loading

- The runtime loads the declared artifacts and instantiates the declared `handler` classes; nothing
  undeclared is picked up from the classpath.
- A missing class, a wrong interface or a checksum mismatch fails startup.
- In codegen mode the same interfaces are used; generated code calls them at the same points.

## 5. Security considerations

Extensions run with the server's permissions. Builders are responsible for their code; operators see
in `manifest.json` and `OPERATIONS.md` exactly which extensions a package contains (class names and
checksums).
