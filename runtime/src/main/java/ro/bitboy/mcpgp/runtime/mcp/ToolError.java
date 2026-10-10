package ro.bitboy.mcpgp.runtime.mcp;

/**
 * A failed tool call with an error code from audit-and-errors.md §3. Returned to the agent as
 * {@code isError: true}; never carries stack traces, credentials or full upstream bodies.
 */
public final class ToolError extends RuntimeException {

    private final String code;
    private final String hint;
    private java.util.Map<String, Object> meta = java.util.Map.of();
    private java.util.Map<String, Object> details = java.util.Map.of();

    public ToolError(String code, String message, String hint) {
        super(message, null, false, false);
        this.code = code;
        this.hint = hint;
    }

    public ToolError(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }

    public String hint() {
        return hint;
    }

    /** {@code _meta} for the error result (instance, contract, correlation id). */
    public ToolError withMeta(java.util.Map<String, Object> meta) {
        this.meta = java.util.Map.copyOf(meta);
        return this;
    }

    public java.util.Map<String, Object> meta() {
        return meta;
    }

    /** Extra fields of the structured error, e.g. the confirmation token. */
    public ToolError withDetails(java.util.Map<String, Object> details) {
        this.details = java.util.Map.copyOf(details);
        return this;
    }

    public java.util.Map<String, Object> details() {
        return details;
    }
}
