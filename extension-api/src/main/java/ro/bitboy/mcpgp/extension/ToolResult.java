package ro.bitboy.mcpgp.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.Objects;

/**
 * A tool's result (tools-contract §6): structured content (and its JSON as text), plain text, or an
 * error with a code the agent can act on.
 */
public final class ToolResult {

    private final JsonNode structured;
    private final String text;
    private final String code;
    private final String message;

    private ToolResult(JsonNode structured, String text, String code, String message) {
        this.structured = structured;
        this.text = text;
        this.code = code;
        this.message = message;
    }

    /** A result with structured content (an object or an array). */
    public static ToolResult of(JsonNode structured) {
        return new ToolResult(Objects.requireNonNull(structured, "structured"), null, null, null);
    }

    /** A result that is plain text. */
    public static ToolResult text(String text) {
        return new ToolResult(null, Objects.requireNonNull(text, "text"), null, null);
    }

    /** An error result; {@code code} is upper snake case, e.g. {@code NOT_ALLOWED}. */
    public static ToolResult error(String code, String message) {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("code must be UPPER_SNAKE_CASE, got " + code);
        }
        return new ToolResult(null, null, code, Objects.requireNonNull(message, "message"));
    }

    public boolean isError() {
        return code != null;
    }

    /** The structured content; null for text and error results. */
    public JsonNode structured() {
        return structured;
    }

    /** The text of a text result; null otherwise. */
    public String text() {
        return text;
    }

    /** The error code; null unless {@link #isError()}. */
    public String code() {
        return code;
    }

    /** The error message; null unless {@link #isError()}. */
    public String message() {
        return message;
    }

    /** The structured content, or the text as a JSON string, or the error as {@code {code, message}}. */
    public JsonNode asJson() {
        if (structured != null) {
            return structured;
        }
        if (text != null) {
            return JsonNodeFactory.instance.textNode(text);
        }
        return JsonNodeFactory.instance.objectNode().put("code", code).put("message", message);
    }

    @Override
    public String toString() {
        return isError() ? "ToolResult[error " + code + ": " + message + "]" : "ToolResult[" + asJson() + "]";
    }
}
