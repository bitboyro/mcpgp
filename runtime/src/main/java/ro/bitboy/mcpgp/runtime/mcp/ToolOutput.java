package ro.bitboy.mcpgp.runtime.mcp;

import java.util.Map;

/**
 * A tool's result: optional {@code structuredContent}, the text shown to the model (JSON of the
 * structured value when null), and {@code _meta} (tools-contract §6).
 */
public record ToolOutput(Object structured, String text, Map<String, Object> meta, String wrappedAs) {
    public ToolOutput(Object structured, String text, Map<String, Object> meta) {
        this(structured, text, meta, null);
    }

    /** The answer as the API gave it: a list or a value comes back out of its {@code items} / {@code value} wrapper. */
    public Object answer() {
        return wrappedAs != null && structured instanceof Map<?, ?> map ? map.get(wrappedAs) : structured;
    }
}
