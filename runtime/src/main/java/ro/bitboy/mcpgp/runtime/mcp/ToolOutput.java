package ro.bitboy.mcpgp.runtime.mcp;

import java.util.Map;

/**
 * A tool's result: optional {@code structuredContent}, the text shown to the model (JSON of the
 * structured value when null), and {@code _meta} (tools-contract §6).
 */
public record ToolOutput(Object structured, String text, Map<String, Object> meta) {
}
