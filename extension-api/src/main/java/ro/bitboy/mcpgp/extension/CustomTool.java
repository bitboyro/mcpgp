package ro.bitboy.mcpgp.extension;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool whose behaviour is code (extensions.md §2). Its name, title, description, input schema and
 * annotations are declared in the definition; the arguments have been validated against that schema.
 * An exception becomes an {@code INTERNAL} result with a generic message; details go to the log and
 * the audit, not to the agent.
 */
@FunctionalInterface
public interface CustomTool {
    ToolResult call(ToolContext ctx, JsonNode arguments) throws Exception;
}
