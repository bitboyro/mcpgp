package ro.bitboy.mcpgp.extension;

import com.fasterxml.jackson.databind.JsonNode;

/** Calls an API tool of the same server, as an agent would, through the normal pipeline (extensions.md §2). */
@FunctionalInterface
public interface ApiClient {
    /**
     * @param tool      the tool name, as in tools/list
     * @param arguments the tool's arguments (with {@code instance} in multi-instance servers)
     * @return the tool's result (JSON results, arrays included, as {@link ToolResult#structured()});
     *         an error result for failures, never an exception for them
     */
    ToolResult call(String tool, JsonNode arguments);
}
