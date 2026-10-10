package ro.bitboy.mcpgp.runtime.mcp;

import java.util.ArrayList;
import java.util.List;

/**
 * What one tool call did, filled in by its handler, for the {@code tool.call} audit event
 * (audit-and-errors §1). Never holds credentials or file contents.
 */
public final class CallRecord {

    /** A file the call sent: name, size and SHA-256 only. */
    public record File(String name, long sizeBytes, String sha256) {
    }

    public String api;
    public String instance;
    public String contract;
    public String method;
    /** The templated path, e.g. {@code /customers/{id}}. */
    public String path;
    public Integer status;
    /** The redacted upstream body of a failed call, kept for troubleshooting (runtime-config §4.5). */
    public String errorBody;
    public final List<File> files = new ArrayList<>();
}
