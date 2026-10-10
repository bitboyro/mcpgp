package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;
import ro.bitboy.mcpgp.runtime.mcp.ToolOutput;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final List<String> secrets;

    ResponseMapper(int responseLimitKb, String errorDetail, Set<String> secrets) {
        this.limitBytes = responseLimitKb * 1024;
        this.fullErrors = !"summary".equals(errorDetail);
        // longest first, so a secret containing another is replaced whole
        this.secrets = secrets.stream().filter(s -> s.length() >= 4).sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    ToolOutput map(HttpResponse<byte[]> response, String instance, Map<String, Object> meta) {
        int status = response.statusCode();
        byte[] body = response.body() == null ? new byte[0] : response.body();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        meta.put("mcpgp/status", status);

        if (status >= 200 && status < 300) {
            return success(body, contentType, response, meta);
        }
        String code = status == 401 || status == 403 ? "AUTH_DOWNSTREAM" : status >= 500 ? "UPSTREAM_5XX" : "UPSTREAM_4XX";
        String hint = switch (code) {
            case "AUTH_DOWNSTREAM" -> "The server's credentials for " + instance + " were rejected or lack permission; ask the operator";
            case "UPSTREAM_5XX" -> "The target server failed; trying again later may help";
            default -> status >= 300 && status < 400 ? "The target answered with a redirect, which is not followed" : null;
        };
        String detail = body.length == 0 ? "" : ": " + (fullErrors ? truncate(text(body)) : summary(body, contentType));
        throw new ToolError(code, "HTTP " + status + " from " + instance + detail, hint).withMeta(meta);
    }

    private ToolOutput success(byte[] body, String contentType, HttpResponse<byte[]> response, Map<String, Object> meta) {
        if (body.length == 0) {
            return new ToolOutput(Map.of("status", response.statusCode()), "HTTP " + response.statusCode() + ", no content", meta);
        }
        String type = contentType.split(";")[0].strip().toLowerCase();
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
                return new ToolOutput(null, truncate(text), meta);
            }
            Object structured = value.isObject() ? JSON.convertValue(value, Map.class) : null;
            return new ToolOutput(structured, text, meta);
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

    String redact(String text) {
        String result = text;
        for (String secret : secrets) {
            result = result.replace(secret, "***");
        }
        return result;
    }

    private String text(byte[] body) {
        return redact(new String(body, StandardCharsets.UTF_8));
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
