package ro.bitboy.mcpgp.runtime.mcp;

/**
 * A failed tool call with an error code from audit-and-errors.md §3. Returned to the agent as
 * {@code isError: true}; never carries stack traces, credentials or full upstream bodies.
 */
public final class ToolError extends RuntimeException {

    private final String code;
    private final String hint;

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
}
