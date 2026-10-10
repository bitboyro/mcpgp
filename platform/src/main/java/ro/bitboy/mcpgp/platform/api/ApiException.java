package ro.bitboy.mcpgp.platform.api;

import java.util.Map;

/** An API error, returned as {@code application/problem+json} (RFC 9457) with a stable {@code code}. */
public final class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public ApiException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what + " not found");
    }

    public static ApiException forbidden(String scope) {
        return new ApiException(403, "FORBIDDEN", "this needs the scope " + scope);
    }

    public static ApiException validation(String message) {
        return new ApiException(422, "VALIDATION", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
