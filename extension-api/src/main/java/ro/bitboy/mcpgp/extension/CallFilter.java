package ro.bitboy.mcpgp.extension;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Runs around a tool call (extensions.md §2–3): it may inspect or change the arguments, change the
 * result, or stop the call by returning a result without calling {@link Chain#proceed}. Filters run
 * before validation and confirmation; lower {@code order} first. An exception becomes an
 * {@code INTERNAL} result.
 */
@FunctionalInterface
public interface CallFilter {
    ToolResult filter(ToolContext ctx, JsonNode arguments, Chain chain) throws Exception;

    /** The rest of the call: the next filters, then validation, confirmation and the tool itself. */
    @FunctionalInterface
    interface Chain {
        ToolResult proceed(JsonNode arguments) throws Exception;
    }
}
