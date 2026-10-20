package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pagination of the list operations ({@code tools/list}, {@code resources/list},
 * {@code resources/templates/list}, {@code prompts/list}; MCP pagination utility). The SDK answers every
 * list in one page, so the transports apply the page size to its answer: a request's cursor is noted on
 * the way in ({@link #accept}), and the answer is cut to one page with a {@code nextCursor} on the way out
 * ({@link #page}). A cursor is opaque to the client: the position and a fingerprint of the list it was
 * made for, so a cursor from before the list changed is refused (invalid params), as the spec says.
 */
public final class ListPager {

    /** The list methods and the field of their result that holds the items. */
    private static final Map<String, String> FIELDS = Map.of(
            "tools/list", "tools",
            "resources/list", "resources",
            "resources/templates/list", "resourceTemplates",
            "prompts/list", "prompts");
    /** Requests read but not answered: scope|id → the method and the cursor the client sent ("" for the first page). */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ListPager() {
    }

    /** A cursor that isn't one this server made for the current list. */
    public static final class InvalidCursor extends RuntimeException {
        InvalidCursor() {
            super("Invalid cursor", null, false, false);
        }
    }

    /** A list request waiting for its answer. */
    public record Pending(String method, String cursor) {
    }

    public static boolean isList(String method) {
        return FIELDS.containsKey(method);
    }

    /**
     * Notes a list request read from a client, when paging is on.
     *
     * @return the key to {@link #take} the cursor with when the answer is written, or null when not a list request
     */
    public static String accept(String scope, JsonNode message, int pageSize) {
        if (pageSize <= 0 || message == null || !message.isObject() || !isList(message.path("method").asText())
                || !message.hasNonNull("id")) {
            return null;
        }
        String key = Inflight.key(scope, message.get("id"));
        PENDING.put(key, new Pending(message.get("method").asText(), message.at("/params/cursor").asText("")));
        return key;
    }

    /** What was noted for an answer, or null when the request wasn't a list request. */
    public static Pending take(String key) {
        return key == null ? null : PENDING.remove(key);
    }

    /**
     * Cuts a list result to one page, in place.
     *
     * @param result the JSON-RPC {@code result} of a list answer
     * @param cursor from {@link #take}: empty for the first page
     * @throws InvalidCursor when the cursor wasn't made for this list
     */
    public static void page(String method, ObjectNode result, String cursor, int pageSize) {
        String field = FIELDS.get(method);
        if (field == null || !(result.get(field) instanceof ArrayNode items)) {
            return;
        }
        String fingerprint = fingerprint(items);
        int from = cursor == null || cursor.isEmpty() ? 0 : decode(cursor, fingerprint, items.size());
        int to = Math.min(items.size(), from + pageSize);
        ArrayNode page = JSON.createArrayNode();
        for (int i = from; i < to; i++) {
            page.add(items.get(i));
        }
        result.set(field, page);
        if (to < items.size()) {
            result.put("nextCursor", encode(to, fingerprint));
        } else {
            result.remove("nextCursor");
        }
    }

    private static String encode(int position, String fingerprint) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((position + ":" + fingerprint).getBytes(StandardCharsets.UTF_8));
    }

    private static int decode(String cursor, String fingerprint, int size) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", 2);
            int position = Integer.parseInt(parts[0]);
            if (parts.length != 2 || !parts[1].equals(fingerprint) || position < 0 || position > size) {
                throw new InvalidCursor();
            }
            return position;
        } catch (IllegalArgumentException e) {
            throw new InvalidCursor();
        }
    }

    /** What identifies the list: its items' names or URIs, in order. */
    private static String fingerprint(ArrayNode items) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (JsonNode item : items) {
                String id = item.has("name") ? item.get("name").asText() : item.path("uriTemplate").asText(item.path("uri").asText());
                digest.update(id.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A JSON-RPC answer for the page, or the invalid-params error when the cursor is refused. */
    public static JsonNode answer(Pending pending, JsonNode response, int pageSize) {
        if (!response.isObject() || !(response.get("result") instanceof ObjectNode result)) {
            return response;
        }
        try {
            page(pending.method(), result, pending.cursor(), pageSize);
            return response;
        } catch (InvalidCursor e) {
            ObjectNode error = JSON.createObjectNode().put("jsonrpc", "2.0");
            error.set("id", response.get("id"));
            error.putObject("error").put("code", -32602).put("message", "Invalid params: the cursor is not valid for this list");
            return error;
        }
    }
}
