# Guide: writing an extension

Most servers need no code. When the definition can't express something (a tool that combines several
calls, a header every request needs, a check before a call goes out), add an extension. The full rules
are in [spec/extensions.md](../spec/extensions.md).

## 1. The code

A plain Java 21 library that depends on the extension API with scope `provided` (the server brings it,
and Jackson, at runtime):

```xml
<dependency>
  <groupId>ro.bitboy.mcpgp</groupId>
  <artifactId>mcpgp-extension-api</artifactId>
  <version>0.3.0</version>
  <scope>provided</scope>
</dependency>
```

```java
package com.acme.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import ro.bitboy.mcpgp.extension.*;

/** A custom tool: open orders and overdue invoices of one customer, in one call. */
public class CustomerHealthTool implements CustomTool {
    public ToolResult call(ToolContext ctx, JsonNode arguments) {
        JsonNode id = arguments.path("customerId");
        ToolResult orders = ctx.api().call("orders_order_search",
                JsonNodeFactory.instance.objectNode().set("query", JsonNodeFactory.instance.objectNode().set("customerId", id)));
        if (orders.isError()) {
            return orders;
        }
        return ToolResult.of(JsonNodeFactory.instance.objectNode().put("openOrders", orders.structured().size()));
    }
}

/** A request enricher: a header every call needs. */
public class TenantHeaderEnricher implements RequestEnricher {
    public void enrich(ToolContext ctx, OutgoingRequest request) {
        request.setHeader("X-Tenant", "acme");
    }
}

/** A call filter: stop large refunds before they are sent. */
public class RefundLimitFilter implements CallFilter {
    public ToolResult filter(ToolContext ctx, JsonNode arguments, Chain chain) throws Exception {
        if (arguments.at("/body/amount").asDouble() > 1000) {
            return ToolResult.error("REFUND_TOO_LARGE", "Refunds over 1000 need a person");
        }
        return chain.proceed(arguments);
    }
}
```

Build the jar and put it in the definition project, e.g. `extensions/acme-orders-ext-1.2.0.jar`.

## 2. The definition

```yaml
extensions:
  artifacts:
    - path: extensions/acme-orders-ext-1.2.0.jar
  customTools:
    - name: customer_health_summary
      description: Open orders of one customer.
      inputSchema: { type: object, properties: { customerId: { type: string } }, required: [customerId] }
      annotations: { readOnlyHint: true }
      handler: com.acme.orders.CustomerHealthTool
  enrichers:
    - handler: com.acme.orders.TenantHeaderEnricher
  callFilters:
    - handler: com.acme.orders.RefundLimitFilter
      match: { operationId: refundOrder }
```

## 3. Build and test

```bash
mcpgp validate orders.mcpgp.yaml
mcpgp test orders.mcpgp.yaml          # the packaged server, with the jar, against a mock of the API
mcpgp build orders.mcpgp.yaml
```

Test cases call custom tools like any other (`tool: orders_customer_health_summary`). On the
platform, upload the project as a zip (the jar included) or `mcpgp push` it; the build embeds the jar,
and `docs/OPERATIONS.md` in the package tells operators which code it contains.
