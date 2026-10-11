package ro.bitboy.mcpgp.extension;

/**
 * Changes the outgoing HTTP request of an API tool call: headers and query parameters only
 * (extensions.md §2–3). It runs after validation and confirmation, right before the request is sent.
 * It must be cheap and must not fail the call: an exception is logged, the enricher is skipped and the
 * call proceeds.
 */
@FunctionalInterface
public interface RequestEnricher {
    void enrich(ToolContext ctx, OutgoingRequest request);
}
