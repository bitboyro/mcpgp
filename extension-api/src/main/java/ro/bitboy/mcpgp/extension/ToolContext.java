package ro.bitboy.mcpgp.extension;

import java.util.Optional;

/**
 * What an extension may know about the call (extensions.md §2): read-only, and never credentials,
 * confirmation tokens or the raw runtime configuration.
 */
public interface ToolContext {

    /** The tool being called. */
    String tool();

    /** The instance the call goes to, once it is known (API tools; empty in filters before the call is resolved). */
    Optional<String> instance();

    /** The contract version the instance speaks, once it is known. */
    Optional<String> contract();

    /** The id that the call's result and audit event carry. */
    String correlationId();

    /** Who called: the client key name or OAuth subject, when the server knows it. */
    Optional<String> caller();

    /** A logger whose messages go through the server's secret redaction. */
    Log log();

    /**
     * Calls API tools of this server through the normal pipeline (validation, sign-in, TLS, audit).
     * Only custom tools get one; elsewhere this throws {@link IllegalStateException}.
     */
    ApiClient api();

    /** Messages for the server log, redacted like the server's own. */
    interface Log {
        void info(String message);

        void warn(String message);

        void error(String message, Throwable error);
    }
}
