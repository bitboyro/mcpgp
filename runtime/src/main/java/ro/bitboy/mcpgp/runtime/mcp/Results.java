package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tool results in the shape of tools-contract §6. */
public final class Results {

    static final ObjectMapper JSON = new ObjectMapper();

    private Results() {
    }

    /** A success: the value as JSON text and as {@code structuredContent} (when it is an object). */
    public static McpSchema.CallToolResult ok(Object value, Map<String, Object> meta) {
        if (value instanceof ToolOutput output) {
            String text = output.text() != null ? output.text() : json(output.structured());
            return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false, output.structured(),
                    output.meta() == null || output.meta().isEmpty() ? null : output.meta());
        }
        String text = json(value);
        Object structured = value instanceof Map<?, ?> ? value : null;
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false, structured,
                meta == null || meta.isEmpty() ? null : meta);
    }

    /** A failure: readable text plus {@code {code, message, hint}} as structured content. */
    public static McpSchema.CallToolResult error(ToolError error, Map<String, Object> meta) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("code", error.code());
        structured.put("message", error.getMessage());
        if (error.hint() != null) {
            structured.put("hint", error.hint());
        }
        String text = error.code() + ": " + error.getMessage() + (error.hint() == null ? "" : "\nHint: " + error.hint());
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), true, structured,
                meta == null || meta.isEmpty() ? null : meta);
    }

    static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
