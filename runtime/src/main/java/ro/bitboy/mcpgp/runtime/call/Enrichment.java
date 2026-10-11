package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.extension.OutgoingRequest;
import ro.bitboy.mcpgp.extension.RequestEnricher;
import ro.bitboy.mcpgp.extension.ToolContext;
import ro.bitboy.mcpgp.runtime.mcp.CallRecord;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Request enrichers (extensions.md §2–3): run in order on the request an API tool call is about to
 * send, right after validation and confirmation. An enricher may set headers and add query parameters,
 * never credentials or transport headers. One that throws is logged and skipped, and its changes are
 * dropped; the call proceeds.
 */
public final class Enrichment {

    private static final Logger LOG = LoggerFactory.getLogger(Enrichment.class);
    private static final Set<String> RESERVED = Set.of("authorization", "proxy-authorization", "cookie", "set-cookie", "host",
            "content-type", "content-length", "transfer-encoding", "connection", "expect", "upgrade", "te", "trailer");

    /** Builds the context an enricher sees for a call. */
    @FunctionalInterface
    public interface Contexts {
        ToolContext of(McpSyncServerExchange exchange, JsonNode tool, CallRecord record);
    }

    public static final Enrichment NONE = new Enrichment(List.of(), null);

    private final List<RequestEnricher> enrichers;
    private final Contexts contexts;

    public Enrichment(List<RequestEnricher> enrichers, Contexts contexts) {
        this.enrichers = List.copyOf(enrichers);
        this.contexts = contexts;
    }

    RequestBuilder.UpstreamRequest apply(McpSyncServerExchange exchange, JsonNode tool, CallRecord record, RequestBuilder.UpstreamRequest request) {
        if (enrichers.isEmpty()) {
            return request;
        }
        ToolContext ctx = contexts.of(exchange, tool, record);
        RequestBuilder.UpstreamRequest current = request;
        for (RequestEnricher enricher : enrichers) {
            Mutable mutable = new Mutable(current, record.path);
            try {
                enricher.enrich(ctx, mutable);
                current = mutable.build();
            } catch (RuntimeException e) {
                LOG.warn("Request enricher {} failed for {}; skipped: {}", enricher.getClass().getName(), tool.path("name").asText(), e.toString());
            }
        }
        return current;
    }

    /** The request as an enricher changes it. */
    static final class Mutable implements OutgoingRequest {
        private final RequestBuilder.UpstreamRequest request;
        private final String pathTemplate;
        private final List<Map.Entry<String, String>> headers;
        private final StringBuilder addedQuery = new StringBuilder();

        Mutable(RequestBuilder.UpstreamRequest request, String pathTemplate) {
            this.request = request;
            this.pathTemplate = pathTemplate;
            this.headers = new ArrayList<>(request.headers());
        }

        @Override
        public String method() {
            return request.method();
        }

        @Override
        public String pathTemplate() {
            return pathTemplate;
        }

        @Override
        public Optional<String> header(String name) {
            if (reserved(name)) {
                return Optional.empty();
            }
            return headers.stream().filter(h -> h.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst();
        }

        @Override
        public void setHeader(String name, String value) {
            if (name == null || !name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) {
                throw new IllegalArgumentException("not a header name: " + name);
            }
            if (reserved(name)) {
                throw new IllegalArgumentException("enrichers can't set " + name);
            }
            if (value == null || value.contains("\r") || value.contains("\n")) {
                throw new IllegalArgumentException("a header value can't be null or contain line breaks");
            }
            headers.removeIf(h -> h.getKey().equalsIgnoreCase(name));
            headers.add(new AbstractMap.SimpleImmutableEntry<>(name, value));
        }

        @Override
        public List<String> query(String name) {
            List<String> values = new ArrayList<>();
            String raw = request.uri().getRawQuery();
            String all = (raw == null ? "" : raw) + addedQuery;
            for (String pair : all.split("&")) {
                int eq = pair.indexOf('=');
                if (!pair.isEmpty() && URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                    values.add(eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            return values;
        }

        @Override
        public void addQuery(String name, String value) {
            if (name == null || name.isEmpty() || value == null) {
                throw new IllegalArgumentException("a query parameter needs a name and a value");
            }
            addedQuery.append('&').append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }

        RequestBuilder.UpstreamRequest build() {
            URI uri = request.uri();
            if (!addedQuery.isEmpty()) {
                String text = uri.toString();
                String added = addedQuery.substring(1);
                uri = URI.create(text + (uri.getRawQuery() == null ? "?" : "&") + added);
            }
            return new RequestBuilder.UpstreamRequest(request.method(), uri, List.copyOf(headers), request.body(), request.contentType());
        }

        private static boolean reserved(String name) {
            return RESERVED.contains(name.toLowerCase(Locale.ROOT));
        }
    }
}
