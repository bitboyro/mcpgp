package ro.bitboy.mcpgp.cli;

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
 * The mock target of {@code mcpgp test} (ci-pipeline.md §5): an HTTP server on the loopback address that
 * answers every operation of the contracts with its first success response, using the spec's example
 * when there is one and a value made from the response schema otherwise. It never calls anything.
 */
final class SpecMock implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int MAX_DEPTH = 8;

    /** One operation the mock answers: method and the full path (base path included) as a pattern. */
    record Route(String method, Pattern path, ApiModel.Operation operation) {
    }

    private final List<Route> routes = new ArrayList<>();
    private final HttpServer server;

    SpecMock(Collection<ApiModel.Contract> contracts) throws IOException {
        for (ApiModel.Contract contract : contracts) {
            for (ApiModel.Operation operation : contract.operations()) {
                routes.add(new Route(operation.method().toUpperCase(), pattern(contract.basePath() + operation.path()), operation));
            }
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL of the mock instance. */
    String address() {
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

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getRawPath();
            String method = exchange.getRequestMethod().toUpperCase();
            // a literal path wins over a template (/customers/search before /customers/{id})
            Route route = routes.stream().filter(r -> r.method().equals(method) && r.path().matcher(path).matches())
                    .min((a, b) -> Integer.compare(variables(a), variables(b))).orElse(null);
            if (route == null) {
                send(exchange, 404, "application/json", "{\"error\":\"the mock has no operation " + method + " " + path + "\"}");
                return;
            }
            ApiModel.Response response = success(route.operation());
            int status = response == null ? 200 : status(response.status());
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
            JsonNode body = media.example() != null ? media.example() : value(media.schema(), 0);
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
    static JsonNode value(JsonNode schema, int depth) {
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
                        if (value(part, depth + 1) instanceof ObjectNode object) {
                            merged.setAll(object);
                        }
                    });
                    return merged;
                }
                return value(schema.path(key).get(0), depth + 1);
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
                    schema.path("properties").fields().forEachRemaining(p -> object.set(p.getKey(), value(p.getValue(), depth + 1)));
                }
                yield object;
            }
            case "array" -> {
                ArrayNode array = NODES.arrayNode();
                if (depth < MAX_DEPTH) {
                    array.add(value(schema.path("items"), depth + 1));
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
