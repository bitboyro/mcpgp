package ro.bitboy.mcpgp.extension;

import java.util.List;
import java.util.Optional;

/**
 * The HTTP request an API tool call is about to send, as an enricher may change it: headers and query
 * parameters. Credentials and transport headers ({@code Authorization}, {@code Cookie}, {@code Host},
 * {@code Content-Type}, {@code Content-Length}, …) can be neither read nor set.
 */
public interface OutgoingRequest {

    String method();

    /** The operation's path template, e.g. {@code /customers/{id}}. */
    String pathTemplate();

    Optional<String> header(String name);

    /** Sets a header, replacing any value it has. Throws {@link IllegalArgumentException} for a reserved header. */
    void setHeader(String name, String value);

    List<String> query(String name);

    /** Adds a query parameter (it is encoded). */
    void addQuery(String name, String value);
}
