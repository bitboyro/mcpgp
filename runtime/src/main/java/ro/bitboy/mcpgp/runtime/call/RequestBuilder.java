package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.audit.Arguments;
import ro.bitboy.mcpgp.runtime.mcp.CallRecord;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns grouped tool arguments into the HTTP request of one variant (tools-contract §2, §13): each
 * group goes to its place, the body is encoded as the bound content type, and a body that can't be
 * encoded stops the call before anything is sent (§13.3).
 */
final class RequestBuilder {

    /** The request to send. {@code body} is null for requests without one. */
    record UpstreamRequest(String method, URI uri, List<Map.Entry<String, String>> headers, byte[] body, String contentType) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FileInputs files;

    RequestBuilder(FileInputs files) {
        this.files = files;
    }

    /** @param record receives the files the request sends (name, size, SHA-256), for the audit event */
    UpstreamRequest build(JsonNode variant, JsonNode arguments, URI base, CallRecord record) {
        String path = variant.path("path").asText();
        StringBuilder query = new StringBuilder();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        List<String> cookies = new ArrayList<>();

        for (JsonNode parameter : variant.path("parameters")) {
            String name = parameter.path("name").asText();
            String in = parameter.path("in").asText();
            JsonNode value = arguments.path(in).get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            boolean explode = parameter.path("explode").asBoolean(in.equals("query") || in.equals("cookie"));
            String style = parameter.path("style").asText(in.equals("query") || in.equals("cookie") ? "form" : "simple");
            switch (in) {
                case "path" -> {
                    String text = simple(value, explode);
                    if (text.isEmpty() || text.equals(".") || text.equals("..")) {
                        throw new ToolError("INPUT_VALIDATION", "path." + name + " may not be empty, '.' or '..'");
                    }
                    path = path.replace("{" + name + "}", encodePath(text));
                }
                case "query" -> appendQuery(query, name, value, style, explode);
                case "header" -> headers.add(Map.entry(name, simple(value, explode)));
                case "cookie" -> cookies.add(name + "=" + simple(value, false));
                default -> {
                }
            }
        }
        if (!cookies.isEmpty()) {
            headers.add(Map.entry("Cookie", String.join("; ", cookies)));
        }
        List<String> accept = new ArrayList<>();
        variant.path("accept").forEach(a -> accept.add(a.asText()));
        if (!accept.isEmpty()) {
            headers.add(Map.entry("Accept", String.join(", ", accept)));
        }

        byte[] body = null;
        String contentType = null;
        JsonNode bodySpec = variant.get("body");
        JsonNode bodyValue = arguments.get("body");
        if (bodySpec != null && bodySpec.isObject() && bodyValue != null && !bodyValue.isNull()) {
            String bound = bodySpec.path("contentType").asText();
            Encoded encoded = encode(bound, bodySpec, bodyValue, record);
            check(bound, bodySpec, bodyValue, encoded);
            body = encoded.bytes();
            contentType = encoded.contentType();
        }
        String full = base.toString() + path + (query.isEmpty() ? "" : "?" + query);
        return new UpstreamRequest(variant.path("method").asText(), URI.create(full), headers, body, contentType);
    }

    // ---------------------------------------------------------------------- parameters

    /** {@code simple} style: arrays as {@code a,b}; objects as {@code k,v,k2,v2} or {@code k=v,k2=v2} exploded. */
    private static String simple(JsonNode value, boolean explode) {
        if (value.isArray()) {
            List<String> items = new ArrayList<>();
            value.forEach(v -> items.add(scalar(v)));
            return String.join(",", items);
        }
        if (value.isObject()) {
            List<String> items = new ArrayList<>();
            value.fields().forEachRemaining(f -> items.add(explode ? f.getKey() + "=" + scalar(f.getValue()) : f.getKey() + "," + scalar(f.getValue())));
            return String.join(",", items);
        }
        return scalar(value);
    }

    private static void appendQuery(StringBuilder query, String name, JsonNode value, String style, boolean explode) {
        if (value.isArray()) {
            if (explode && style.equals("form")) {
                value.forEach(v -> pair(query, name, scalar(v)));
            } else {
                String separator = switch (style) {
                    case "spaceDelimited" -> " ";
                    case "pipeDelimited" -> "|";
                    default -> ",";
                };
                List<String> items = new ArrayList<>();
                value.forEach(v -> items.add(scalar(v)));
                pair(query, name, String.join(separator, items));
            }
        } else if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            if (style.equals("deepObject")) {
                fields.forEachRemaining(f -> pair(query, name + "[" + f.getKey() + "]", scalar(f.getValue())));
            } else if (explode) {
                fields.forEachRemaining(f -> pair(query, f.getKey(), scalar(f.getValue())));
            } else {
                pair(query, name, simple(value, false));
            }
        } else {
            pair(query, name, scalar(value));
        }
    }

    private static void pair(StringBuilder query, String name, String value) {
        if (!query.isEmpty()) {
            query.append('&');
        }
        query.append(encodeQuery(name)).append('=').append(encodeQuery(value));
    }

    private static String scalar(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static String encodeQuery(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20").replace("%2C", ",");
    }

    // ---------------------------------------------------------------------- bodies

    private record Encoded(byte[] bytes, String contentType, List<String> partNames) {
    }

    private Encoded encode(String bound, JsonNode bodySpec, JsonNode value, CallRecord record) {
        String type = bound.toLowerCase();
        if (type.startsWith("multipart/")) {
            return multipart(bound, bodySpec, value, record);
        }
        if (type.equals("application/x-www-form-urlencoded")) {
            StringBuilder form = new StringBuilder();
            value.fields().forEachRemaining(f -> {
                if (f.getValue().isArray()) {
                    f.getValue().forEach(v -> pair(form, f.getKey(), scalar(v)));
                } else if (!f.getValue().isNull()) {
                    pair(form, f.getKey(), scalar(f.getValue()));
                }
            });
            return new Encoded(form.toString().getBytes(StandardCharsets.UTF_8), bound, List.of());
        }
        if (isJson(type)) {
            return new Encoded(json(value), bound, List.of());
        }
        if (value.isObject() && (value.has("base64") || value.has("path"))) {
            FileInputs.FileData file = noted(record, files.read(value, List.of(bound), "body"));
            return new Encoded(file.bytes(), file.contentType(), List.of());
        }
        if (value.isTextual()) {
            return new Encoded(value.asText().getBytes(StandardCharsets.UTF_8), bound, List.of());
        }
        return new Encoded(json(value), bound, List.of());
    }

    private Encoded multipart(String bound, JsonNode bodySpec, JsonNode value, CallRecord record) {
        String boundary = "mcpgp-" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> names = new ArrayList<>();
        for (JsonNode part : bodySpec.path("parts")) {
            String name = part.path("name").asText();
            JsonNode partValue = value.get(name);
            if (partValue == null || partValue.isNull()) {
                continue;
            }
            List<String> types = new ArrayList<>();
            part.path("contentTypes").forEach(t -> types.add(t.asText()));
            if (part.path("file").asBoolean(false)) {
                List<JsonNode> fileValues = new ArrayList<>();
                if (partValue.isArray()) {
                    partValue.forEach(fileValues::add);
                } else {
                    fileValues.add(partValue);
                }
                for (int i = 0; i < fileValues.size(); i++) {
                    String field = "body." + name + (partValue.isArray() ? "[" + i + "]" : "");
                    FileInputs.FileData file = noted(record, files.read(fileValues.get(i), types, field));
                    String filename = file.filename() == null ? name : file.filename();
                    writePart(out, boundary, "form-data; name=\"" + quote(name) + "\"; filename=\"" + quote(filename) + "\"",
                            file.contentType(), file.bytes());
                    names.add(name);
                }
            } else {
                String type = types.isEmpty() ? "text/plain" : types.get(0);
                byte[] bytes = isJson(type.toLowerCase()) ? json(partValue)
                        : (partValue.isValueNode() ? partValue.asText() : partValue.toString()).getBytes(StandardCharsets.UTF_8);
                writePart(out, boundary, "form-data; name=\"" + quote(name) + "\"", type, bytes);
                names.add(name);
            }
        }
        write(out, "--" + boundary + "--\r\n");
        return new Encoded(out.toByteArray(), bound + "; boundary=" + boundary, names);
    }

    private static FileInputs.FileData noted(CallRecord record, FileInputs.FileData file) {
        record.files.add(new CallRecord.File(file.filename() == null ? "" : file.filename(), file.bytes().length,
                Arguments.sha256(file.bytes())));
        return file;
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String disposition, String type, byte[] bytes) {
        write(out, "--" + boundary + "\r\nContent-Disposition: " + disposition + "\r\nContent-Type: " + type + "\r\n\r\n");
        out.writeBytes(bytes);
        write(out, "\r\n");
    }

    /** §13.3: a call with body input is never sent with an empty or wrongly encoded body. */
    private static void check(String bound, JsonNode bodySpec, JsonNode value, Encoded encoded) {
        boolean hasInput = value.isContainerNode() ? !value.isEmpty() : !value.asText("").isEmpty();
        if (hasInput && (encoded.bytes() == null || encoded.bytes().length == 0)) {
            throw new ToolError("INTERNAL", "request body could not be encoded as " + bound);
        }
        if (bound.toLowerCase().startsWith("multipart/")) {
            List<String> known = new ArrayList<>();
            bodySpec.path("parts").forEach(p -> known.add(p.path("name").asText()));
            value.fieldNames().forEachRemaining(field -> {
                if (!value.get(field).isNull() && (!known.contains(field) || !encoded.partNames().contains(field))) {
                    throw new ToolError("INTERNAL", "request body could not be encoded as " + bound + " (part " + field + ")");
                }
            });
        }
    }

    static boolean isJson(String type) {
        String base = type.split(";")[0].strip();
        return base.equals("application/json") || base.endsWith("+json");
    }

    private static byte[] json(JsonNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
