package ro.bitboy.mcpgp.packager.trial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ro.bitboy.mcpgp.core.openapi.ApiModel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The mock target of {@code mcpgp test} (ci-pipeline.md §5) and of trying a tool on the platform: an HTTP server on the loopback address that
 * answers every operation of the contracts with its first success response, using the spec's example
 * when there is one and a value made from the response schema otherwise. It never calls anything.
 */
public final class SpecMock implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;

    /** One operation the mock answers: method and the full path (base path included) as a pattern. */
    record Route(String method, Pattern path, ApiModel.Operation operation, java.util.Map<String, ? extends JsonNode> schemas) {
    }

    /** A request the mock received: what the server would have sent to the real API (credentials left out). */
    public record Received(String method, String path, String query, java.util.Map<String, String> headers, String body, int status) {
    }

    /** Credentials, and what the HTTP client adds because the mock speaks plain HTTP (a real https target never sees it). */
    private static final java.util.Set<String> HIDDEN = java.util.Set.of("authorization", "proxy-authorization", "cookie", "upgrade", "http2-settings");
    private static final int KEEP = 20;
    private static final int MAX_BODY = 4096;

    private final List<Route> routes = new ArrayList<>();
    private final java.util.Deque<Received> received = new java.util.ArrayDeque<>();
    private final HttpServer server;

    public SpecMock(Collection<ApiModel.Contract> contracts) throws IOException {
        for (ApiModel.Contract contract : contracts) {
            for (ApiModel.Operation operation : contract.operations()) {
                routes.add(new Route(operation.method().toUpperCase(), pattern(contract.basePath() + operation.path()), operation, contract.schemas()));
            }
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL of the mock instance. */
    public String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Pattern pattern(String path) {
        StringBuilder regex = new StringBuilder("^");
        Matcher m = Pattern.compile("\\{[^}]+}").matcher(path);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start()))).append("[^/]+");
            last = m.end();
        }
        return Pattern.compile(regex.append(Pattern.quote(path.substring(last))).append("/?$").toString());
    }

    /** The requests received since the last call (at most the last 20), oldest first. */
    public synchronized List<Received> drain() {
        List<Received> list = new ArrayList<>(received);
        received.clear();
        return list;
    }

    private synchronized void record(HttpExchange exchange, byte[] body, int status) {
        java.util.Map<String, String> headers = new java.util.TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (!HIDDEN.contains(lower) && !lower.equals("host") && !lower.equals("content-length") && !lower.equals("connection")) {
                headers.put(lower, String.join(", ", values));
            }
        });
        String text = new String(body, 0, Math.min(body.length, MAX_BODY), StandardCharsets.UTF_8) + (body.length > MAX_BODY ? "…" : "");
        received.addLast(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                java.util.Map.copyOf(headers), text, status));
        while (received.size() > KEEP) {
            received.removeFirst();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod().toUpperCase();
            // a literal path wins over a template (/customers/search before /customers/{id})
            Route route = routes.stream().filter(r -> r.method().equals(method) && r.path().matcher(path).matches())
                    .min((a, b) -> Integer.compare(variables(a), variables(b))).orElse(null);
            if (route == null) {
                record(exchange, requestBody, 404);
                send(exchange, 404, "application/json", "{\"error\":\"the mock has no operation " + method + " " + path + "\"}");
                return;
            }
            ApiModel.Response response = success(route.operation());
            int status = response == null ? 200 : status(response.status());
            record(exchange, requestBody, status);
            if (response == null || response.content().isEmpty() || status == 204) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            ApiModel.MediaType media = response.content().stream().filter(c -> c.name().contains("json")).findFirst()
                    .orElse(response.content().get(0));
            String type = media.name().equals("*/*") ? "application/json" : media.name();
            if (!type.contains("json")) {
                send(exchange, status, type, media.example() != null && media.example().isTextual() ? media.example().asText() : "mock");
                return;
            }
            JsonNode body = media.example() != null ? media.example() : value(media.schema(), route.schemas(), 0);
            send(exchange, status, type, JSON.writeValueAsString(body));
        }
    }

    private static int variables(Route route) {
        return route.operation().path().split("\\{", -1).length;
    }

    /** The first 2xx response, else {@code default}. */
    private static ApiModel.Response success(ApiModel.Operation operation) {
        ApiModel.Response fallback = null;
        for (ApiModel.Response r : operation.responses()) {
            if (r.status().startsWith("2")) {
                return r;
            }
            if (r.status().equals("default")) {
                fallback = r;
            }
        }
        return fallback;
    }

    private static int status(String status) {
        return status.matches("\\d{3}") ? Integer.parseInt(status) : 200;
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** A value that fits the schema: its example, default, const or first enum value, else one made by type. */
    public static JsonNode value(JsonNode schema, int depth) {
        return value(schema, java.util.Map.of(), depth);
    }

    /** @param schemas the contract's shared schemas by name, for {@code $ref}s to them */
    public static JsonNode value(JsonNode schema, java.util.Map<String, ? extends JsonNode> schemas, int depth) {
        if (schema != null && schema.has("$ref") && depth < MAX_DEPTH) {
            String ref = schema.path("$ref").asText();
            JsonNode target = schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
            if (target != null) {
                return value(target, schemas, depth + 1);
            }
        }
        if (schema == null || schema.isMissingNode() || !schema.isObject()) {
            return NODES.objectNode();
        }
        for (String key : List.of("example", "default", "const")) {
            if (schema.has(key)) {
                return schema.get(key);
            }
        }
        if (schema.path("examples").isArray() && !schema.path("examples").isEmpty()) {
            return schema.path("examples").get(0);
        }
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return schema.path("enum").get(0);
        }
        for (String key : List.of("allOf", "oneOf", "anyOf")) {
            if (schema.path(key).isArray() && !schema.path(key).isEmpty()) {
                if (key.equals("allOf")) {
                    ObjectNode merged = NODES.objectNode();
                    schema.path(key).forEach(part -> {
                        if (value(part, schemas, depth + 1) instanceof ObjectNode object) {
                            merged.setAll(object);
                        }
                    });
                    return merged;
                }
                return value(schema.path(key).get(0), schemas, depth + 1);
            }
        }
        String type = schema.path("type").isArray() ? firstNonNull(schema.path("type")) : schema.path("type").asText("");
        if (type.isEmpty()) {
            type = schema.has("properties") ? "object" : schema.has("items") ? "array" : "string";
        }
        return switch (type) {
            case "object" -> {
                ObjectNode object = NODES.objectNode();
                if (depth < MAX_DEPTH) {
                    schema.path("properties").fields().forEachRemaining(p -> object.set(p.getKey(), value(p.getValue(), schemas, depth + 1)));
                }
                yield object;
            }
            case "array" -> {
                ArrayNode array = NODES.arrayNode();
                if (depth < MAX_DEPTH) {
                    array.add(value(schema.path("items"), schemas, depth + 1));
                }
                yield array;
            }
            case "integer" -> NODES.numberNode(schema.path("minimum").asLong(1));
            case "number" -> NODES.numberNode(schema.path("minimum").asDouble(1.5));
            case "boolean" -> NODES.booleanNode(true);
            case "null" -> NODES.nullNode();
            default -> NODES.textNode(text(schema.path("format").asText("")));
        };
    }

    private static String firstNonNull(JsonNode types) {
        Iterator<JsonNode> it = types.elements();
        while (it.hasNext()) {
            String t = it.next().asText();
            if (!t.equals("null")) {
                return t;
            }
        }
        return "null";
    }

    private static String text(String format) {
        return switch (format) {
            case "date-time" -> "2026-01-01T00:00:00Z";
            case "date" -> "2026-01-01";
            case "email" -> "mock@example.com";
            case "uuid" -> "00000000-0000-4000-8000-000000000000";
            case "uri", "url" -> "https://example.com/mock";
            case "byte" -> "bW9jaw==";
            default -> "mock";
        };
    }
}
