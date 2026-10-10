package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;

import java.util.Map;

/** Runs one kind of tool. Throws {@link ToolError} for failures the agent should see. */
@FunctionalInterface
public interface ToolHandler {
    /**
     * @param tool      the served tool definition
     * @param arguments the call's arguments (never null)
     * @return the result value (a map becomes {@code structuredContent})
     */
    Object call(McpSyncServerExchange exchange, JsonNode tool, Map<String, Object> arguments);
}
