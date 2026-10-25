package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.mcp.CallRecord;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;
import ro.bitboy.mcpgp.runtime.mcp.ToolOutput;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an upstream response to a tool result (tools-contract §6) or a {@link ToolError}
 * (audit-and-errors §3). Upstream content is redacted of every secret value before it reaches the
 * agent; error bodies are shown in full or summarized ({@code errors.detail}, runtime-config §4.5).
 */
final class ResponseMapper {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FILENAME = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final int SUMMARY_LIMIT = 200;

    private final int limitBytes;
    private final boolean fullErrors;
    private final Redactor redactor;
    private final MaskedFields masked;
    /** A JSON answer over the limit is cut at whole list items (safety.cleanTruncation). */
    private final boolean cleanTruncation;
    /** A target's 429 tells the agent how long to wait (safety.rateLimits.retryAfter). */
    private final boolean retryAfter;

    ResponseMapper(int responseLimitKb, String errorDetail, Redactor redactor) {
        this(responseLimitKb, errorDetail, redactor, true, true);
    }

    ResponseMapper(int responseLimitKb, String errorDetail, Redactor redactor, boolean cleanTruncation, boolean retryAfter) {
        this(responseLimitKb, errorDetail, redactor, cleanTruncation, retryAfter, new MaskedFields(List.of()));
    }

    ResponseMapper(int responseLimitKb, String errorDetail, Redactor redactor, boolean cleanTruncation, boolean retryAfter, MaskedFields masked) {
        this.masked = masked;
        this.limitBytes = responseLimitKb * 1024;
        this.fullErrors = !"summary".equals(errorDetail);
        this.redactor = redactor;
        this.cleanTruncation = cleanTruncation;
        this.retryAfter = retryAfter;
    }

    /** Upstream error bodies kept in the audit event are capped at this size. */
    private static final int AUDIT_BODY_LIMIT = 64 * 1024;

    ToolOutput map(HttpResponse<byte[]> response, String instance, Map<String, Object> meta, CallRecord record) {
        int status = response.statusCode();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        meta.put("mcpgp/status", status);

        if (status >= 200 && status < 300) {
            return success(body, contentType, response, meta);
        }
        String code = status == 401 || status == 403 ? "AUTH_UPSTREAM" : status >= 500 ? "UPSTREAM_5XX" : "UPSTREAM_4XX";
        String hint = switch (code) {
            case "AUTH_UPSTREAM" -> "The server's credentials for " + instance + " were rejected or lack permission; ask the operator";
            case "UPSTREAM_5XX" -> "The target server failed; trying again later may help";
            default -> status >= 300 && status < 400 ? "The target answered with a redirect, which is not followed" : null;
        };
        if (body.length > 0) {
            // the full (redacted) body always goes to the audit file, whatever errors.detail says
            String full = text(body);
            record.errorBody = full.length() > AUDIT_BODY_LIMIT ? full.substring(0, AUDIT_BODY_LIMIT) + "…" : full;
        }
        String detail = body.length == 0 ? "" : ": " + (fullErrors ? truncate(text(body)) : summary(body, contentType));
        if (status == 429 && retryAfter) {
            Long seconds = retryAfterSeconds(response.headers().firstValue("Retry-After").orElse(null));
            if (seconds != null) {
                meta.put("mcpgp/retryAfterSeconds", seconds);
            }
            hint = "The target is limiting calls" + (seconds != null ? ": wait " + seconds + " s before calling " + instance + " again" : "; wait before trying again");
        }
        throw new ToolError(code, "HTTP " + status + " from " + instance + detail, hint).withMeta(meta);
    }

    private ToolOutput success(byte[] body, String contentType, HttpResponse<byte[]> response, Map<String, Object> meta) {
        if (body.length == 0) {
            return new ToolOutput(Map.of("status", response.statusCode()), "HTTP " + response.statusCode() + ", no content", meta);
        }
        String type = contentType.split(";")[0].strip().toLowerCase();
        // the target sent more than the server reads: the end of it was never downloaded
        boolean cutWhileReading = response instanceof CappedResponse capped && capped.cut();
        if (cutWhileReading) {
            String size = ((CappedResponse) response).declaredLength().map(n -> (n / 1024) + " KB").orElse("more than " + (body.length / 1024) + " KB");
            if (!RequestBuilder.isJson(type) && !isText(type, contentType) && !(type.isEmpty() && looksLikeJson(body))) {
                throw new ToolError("RESPONSE_TOO_LARGE", "The file is " + size + "; the limit is " + (limitBytes / 1024) + " KB",
                        "Ask the operator to raise responseLimitKb, or fetch a smaller file").withMeta(meta);
            }
            meta.put("mcpgp/truncated", Map.of("reason", "too large", "limitKb", limitBytes / 1024));
            return new ToolOutput(null, head(redact(new String(body, StandardCharsets.UTF_8))) + "\n[truncated: the answer is " + size
                    + ", over the " + (limitBytes / 1024) + " KB limit; narrow the query (filters, a smaller page) to see the rest]", meta);
        }
        if (RequestBuilder.isJson(type) || type.isEmpty() && looksLikeJson(body)) {
            String text = redact(new String(body, StandardCharsets.UTF_8));
            JsonNode value;
            try {
                value = JSON.readTree(text);
            } catch (IOException e) {
                throw new ToolError("RESPONSE_PARSE_ERROR", "The target answered " + response.statusCode() + " with " + contentType
                        + " that doesn't parse as JSON").withMeta(meta);
            }
            if (body.length > limitBytes) {
                ToolOutput items = cleanTruncation ? cutAtItems(value, body.length, meta) : null;
                if (items != null) {
                    return items;
                }
                meta.put("mcpgp/truncated", Map.of("reason", "too large", "limitKb", limitBytes / 1024));
                return new ToolOutput(null, truncate(text), meta);
            }
            return new ToolOutput(structured(value), text, meta, wrapper(value));
        }
        if (isText(type, contentType)) {
            return new ToolOutput(null, truncate(redact(new String(body, StandardCharsets.UTF_8))), meta);
        }
        // binary: a base64 file object (tools-contract §6, §9)
        if (body.length > limitBytes) {
            throw new ToolError("RESPONSE_TOO_LARGE", "The file is " + (body.length / 1024) + " KB; the limit is " + (limitBytes / 1024) + " KB",
                    "Ask the operator to raise responseLimitKb, or fetch a smaller file").withMeta(meta);
        }
        Map<String, Object> file = new LinkedHashMap<>();
        response.headers().firstValue("Content-Disposition").map(FILENAME::matcher).filter(Matcher::find)
                .ifPresent(m -> file.put("filename", m.group(1)));
        file.put("contentType", type.isEmpty() ? "application/octet-stream" : type);
        file.put("size", body.length);
        file.put("base64", Base64.getEncoder().encodeToString(body));
        return new ToolOutput(file, null, meta);
    }

    /** Retry-After as seconds: a number, or an HTTP date; null when absent or unreadable. */
    static Long retryAfterSeconds(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            try {
                long at = java.time.ZonedDateTime.parse(value.strip(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond();
                return Math.max(0, at - java.time.Instant.now().getEpochSecond());
            } catch (java.time.format.DateTimeParseException again) {
                return null;
            }
        }
    }

    /** A short line: problem+json title/detail, a JSON message field, or the first line of text. */
    private String summary(byte[] body, String contentType) {
        String text = redact(text(body));
        if (contentType.contains("json")) {
            try {
                JsonNode json = JSON.readTree(text);
                for (String field : List.of("title", "detail", "message", "error")) {
                    if (json.path(field).isTextual()) {
                        String line = json.path(field).asText();
                        if (field.equals("title") && json.path("detail").isTextual()) {
                            line += " (" + json.path("detail").asText() + ")";
                        }
                        return cut(line);
                    }
                }
            } catch (IOException e) {
                // not JSON after all: first line below
            }
        }
        return cut(text.lines().findFirst().orElse(""));
    }

    /** Known secret values, then the values of masked fields (MaskedFields), whatever the body's shape. */
    String redact(String text) {
        return masked.maskText(redactor.redact(text));
    }

    private String text(byte[] body) {
        return redact(new String(body, StandardCharsets.UTF_8));
    }

    /**
     * A JSON answer over the limit with whole items of its largest list (the answer itself, or a list
     * in it): as many as fit, and a note with how many there were. Null when no list makes it fit.
     */
    private ToolOutput cutAtItems(JsonNode value, int size, Map<String, Object> meta) {
        String pointer = largestList(value, "", 0);
        if (pointer == null) {
            return null;
        }
        com.fasterxml.jackson.databind.node.ArrayNode list = (com.fasterxml.jackson.databind.node.ArrayNode) value.at(pointer);
        int total = list.size();
        int budget = limitBytes - 400;  // room for the note
        int low = 0;
        int high = total;
        while (low < high) {        // the most items that fit
            int mid = (low + high + 1) / 2;
            if (bytes(withItems(value, pointer, mid)) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        if (low == 0) {
            return null;
        }
        JsonNode cut = withItems(value, pointer, low);
        String where = pointer.isEmpty() ? "the answer" : pointer;
        meta.put("mcpgp/truncated", Map.of("reason", "too large", "limitKb", limitBytes / 1024, "list", pointer.isEmpty() ? "/" : pointer,
                "shown", low, "total", total));
        String note = "[truncated: " + low + " of " + total + " items of " + where + " shown; the answer is " + (size / 1024) + " KB, over the "
                + (limitBytes / 1024) + " KB limit. Narrow the query or ask for a smaller page to see the rest]";
        return new ToolOutput(structured(cut), cut.toString() + "\n" + note, meta, wrapper(cut));
    }

    /**
     * A JSON answer as {@code structuredContent}, which MCP requires to be an object: an object as it
     * is, a list as {@code { items }}, any other value as {@code { value }} (the tool's outputSchema says the same).
     */
    static Map<?, ?> structured(JsonNode value) {
        if (value.isObject()) {
            return JSON.convertValue(value, Map.class);
        }
        return value.isArray() ? Map.of("items", JSON.convertValue(value, List.class))
                : value.isNull() ? null : Map.of("value", JSON.convertValue(value, Object.class));
    }

    /** Which wrapper {@link #structured} puts a value in: null for an object. */
    static String wrapper(JsonNode value) {
        return value.isObject() || value.isNull() ? null : value.isArray() ? "items" : "value";
    }

    /** The JSON pointer of the list with the most bytes, at most two levels down; null without one. */
    private static String largestList(JsonNode value, String at, int depth) {
        if (value.isArray() && value.size() > 1) {
            return at;
        }
        if (!value.isObject() || depth >= 2) {
            return null;
        }
        String best = null;
        long bestSize = 0;
        for (java.util.Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            String found = largestList(field.getValue(), at + "/" + field.getKey().replace("~", "~0").replace("/", "~1"), depth + 1);
            if (found != null) {
                long size = bytes(field.getValue());
                if (size > bestSize) {
                    best = found;
                    bestSize = size;
                }
            }
        }
        return best;
    }

    private static JsonNode withItems(JsonNode value, String pointer, int count) {
        if (pointer.isEmpty()) {
            com.fasterxml.jackson.databind.node.ArrayNode copy = JSON.createArrayNode();
            for (int i = 0; i < count; i++) {
                copy.add(value.get(i));
            }
            return copy;
        }
        JsonNode copy = value.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode list = (com.fasterxml.jackson.databind.node.ArrayNode) copy.at(pointer);
        while (list.size() > count) {
            list.remove(list.size() - 1);
        }
        return copy;
    }

    private static long bytes(JsonNode value) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** The first {@code limitBytes} of a text, without a note. */
    private String head(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return bytes.length <= limitBytes ? text : new String(bytes, 0, limitBytes, StandardCharsets.UTF_8);
    }

    private String truncate(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limitBytes) {
            return text;
        }
        String head = new String(bytes, 0, limitBytes, StandardCharsets.UTF_8);
        return head + "\n[truncated: " + (limitBytes / 1024) + " KB of " + (bytes.length / 1024)
                + " KB shown; narrow the query (filters, a smaller page) to see the rest]";
    }

    private static String cut(String line) {
        return line.length() <= SUMMARY_LIMIT ? line : line.substring(0, SUMMARY_LIMIT - 1) + "…";
    }

    private static boolean isText(String type, String full) {
        return type.startsWith("text/") || type.endsWith("+xml") || type.equals("application/xml") || type.contains("yaml")
                || full.toLowerCase().contains("charset=");
    }

    private static boolean looksLikeJson(byte[] body) {
        for (byte b : body) {
            if (!Character.isWhitespace(b)) {
                return b == '{' || b == '[';
            }
        }
        return false;
    }
}
