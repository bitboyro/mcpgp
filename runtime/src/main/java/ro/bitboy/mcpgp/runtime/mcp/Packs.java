package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.config.BaseUrl;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The P0 support packs (packaging.md §4.1) that need nothing outside the server: audit, introspection,
 * schema and diagnostics. Recording comes later; its tools are not offered until then.
 */
final class Packs {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final Manifest manifest;
    private final RuntimeConfig config;
    private final ToolCatalog catalog;

    Packs(Manifest manifest, RuntimeConfig config, ToolCatalog catalog) {
        this.manifest = manifest;
        this.config = config;
        this.catalog = catalog;
    }

    /** Handlers by the pack tool's base name (without prefix). */
    Map<String, ToolHandler> handlers() {
        Map<String, ToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("audit_list", (exchange, tool, args, record) -> auditList(args));
        handlers.put("explain_tool", (exchange, tool, args, record) -> explain(args));
        handlers.put("manifest_info", (exchange, tool, args, record) -> manifestInfo());
        handlers.put("describe_schema", (exchange, tool, args, record) -> describeSchema(args));
        handlers.put("tls_certificate_fetch", (exchange, tool, args, record) -> certificate(instance(args)));
        handlers.put("connectivity_check", (exchange, tool, args, record) -> connectivity(instance(args)));
        return handlers;
    }

    // ---------------------------------------------------------------------- audit

    private Object auditList(Map<String, Object> args) {
        int limit = args.get("limit") instanceof Number n ? Math.max(1, Math.min(200, n.intValue())) : 50;
        Instant since = time(args.get("since"), "since");
        Instant until = time(args.get("until"), "until");
        List<Map<String, Object>> events = new ArrayList<>();
        for (Path file : auditFiles()) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file);
            } catch (IOException e) {
                continue;
            }
            for (int i = lines.size() - 1; i >= 0; i--) {
                JsonNode event;
                try {
                    event = JSON.readTree(lines.get(i));
                } catch (IOException e) {
                    continue;
                }
                if (!matches(event, "tool", args.get("tool")) || !matches(event, "instance", args.get("instance"))
                        || !matches(event, "outcome", args.get("outcome"))) {
                    continue;
                }
                Instant ts = Instant.parse(event.path("ts").asText("1970-01-01T00:00:00Z"));
                if (since != null && ts.isBefore(since) || until != null && ts.isAfter(until)) {
                    continue;
                }
                events.add(JSON.convertValue(event, Map.class));
            }
        }
        return Paging.page(events, args.get("cursor"), limit);
    }

    /** The current audit file first, then rotated ones, newest first. */
    private List<Path> auditFiles() {
        Path current = config.audit().file().toAbsolutePath();
        List<Path> files = new ArrayList<>();
        if (Files.exists(current)) {
            files.add(current);
        }
        String name = current.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) + "-" : name + "-";
        try (Stream<Path> list = Files.list(current.getParent())) {
            list.filter(p -> p.getFileName().toString().startsWith(base))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            // no audit directory yet
        }
        return files;
    }

    private static boolean matches(JsonNode event, String field, Object wanted) {
        return wanted == null || wanted.toString().equals(event.path(field).asText());
    }

    private static Instant time(Object value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException e) {
            throw new ToolError("INPUT_VALIDATION", field + " must be a date-time like 2026-10-10T08:00:00Z");
        }
    }

    // ---------------------------------------------------------------------- introspection

    private Object explain(Map<String, Object> args) {
        JsonNode tool = catalog.tool(String.valueOf(args.get("tool")))
                .orElseThrow(() -> new ToolError("INPUT_VALIDATION", "No tool named '" + args.get("tool") + "'"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", tool.path("name").asText());
        result.put("kind", tool.path("kind").asText());
        if (tool.has("operation")) {
            result.put("operation", JSON.convertValue(tool.get("operation"), Map.class));
            Map<String, Object> variants = new LinkedHashMap<>();
            tool.path("variants").fields().forEachRemaining(v -> variants.put(v.getKey(),
                    v.getValue().path("method").asText() + " " + v.getValue().path("path").asText()));
            result.put("contracts", variants);
        }
        if (tool.has("provenance")) {
            result.put("provenance", JSON.convertValue(tool.get("provenance"), Map.class));
        }
        return result;
    }

    private Object manifestInfo() {
        JsonNode root = manifest.root();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("server", JSON.convertValue(root.path("server"), Map.class));
        info.put("build", Map.of("createdAt", root.at("/build/createdAt").asText(), "cli", root.at("/build/cli").asText()));
        List<Map<String, Object>> apis = new ArrayList<>();
        manifest.apis().values().forEach(api -> apis.add(Map.of("id", api.id(), "title", api.title(),
                "contracts", List.copyOf(api.contracts().keySet()), "defaultContract", api.defaultContract())));
        info.put("apis", apis);
        info.put("instances", root.at("/instances/mode").asText());
        info.put("exposure", root.at("/exposure/mode").asText());
        List<String> packs = new ArrayList<>();
        root.path("packs").forEach(p -> packs.add(p.path("id").asText()));
        info.put("packs", packs);
        long apiTools = catalog.all().values().stream().filter(t -> t.path("kind").asText().equals("api")).count();
        info.put("tools", Map.of("api", apiTools, "listed", catalog.listed().size()));
        return info;
    }

    // ---------------------------------------------------------------------- schema

    /**
     * The input schema one level deep: properties with type and description; nested objects are
     * listed by name. {@code part} drills into one property path ({@code body.payload}) or a
     * {@code $defs} entry; {@code variant} narrows a {@code oneOf} by index.
     */
    private Object describeSchema(Map<String, Object> args) {
        JsonNode tool = catalog.tool(String.valueOf(args.get("tool")))
                .orElseThrow(() -> new ToolError("INPUT_VALIDATION", "No tool named '" + args.get("tool") + "'"));
        JsonNode schema = tool.path("inputSchema");
        JsonNode defs = schema.path("$defs");
        String part = args.get("part") == null ? null : args.get("part").toString();
        JsonNode node = schema;
        if (part != null) {
            node = defs.has(part) ? defs.get(part) : null;
            if (node == null) {
                node = schema;
                for (String segment : part.split("\\.")) {
                    node = resolve(node, defs).path("properties").path(segment);
                }
                if (node.isMissingNode()) {
                    throw new ToolError("INPUT_VALIDATION", "No part '" + part + "' in " + tool.path("name").asText(),
                            "Use a property path like body.payload, or a definition name");
                }
            }
        }
        node = resolve(node, defs);
        if (args.get("variant") != null) {
            int index;
            try {
                index = Integer.parseInt(args.get("variant").toString());
            } catch (NumberFormatException e) {
                throw new ToolError("INPUT_VALIDATION", "variant must be the index of a oneOf alternative");
            }
            if (!node.path("oneOf").has(index)) {
                throw new ToolError("INPUT_VALIDATION", "This part has no oneOf alternative " + index);
            }
            node = resolve(node.path("oneOf").get(index), defs);
        }
        return summarize(node, defs);
    }

    private static JsonNode resolve(JsonNode node, JsonNode defs) {
        JsonNode current = node;
        for (int i = 0; i < 8 && current.path("$ref").asText("").startsWith("#/$defs/"); i++) {
            current = defs.path(current.get("$ref").asText().substring("#/$defs/".length()));
        }
        return current;
    }

    private static Map<String, Object> summarize(JsonNode node, JsonNode defs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("type", node.path("type").isMissingNode() ? (node.has("oneOf") ? "oneOf" : "any") : JSON.convertValue(node.path("type"), Object.class));
        if (node.has("description")) {
            summary.put("description", node.path("description").asText());
        }
        if (node.has("properties")) {
            List<String> required = new ArrayList<>();
            node.path("required").forEach(r -> required.add(r.asText()));
            Map<String, Object> properties = new LinkedHashMap<>();
            node.path("properties").fields().forEachRemaining(f -> {
                JsonNode p = resolve(f.getValue(), defs);
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("type", p.has("type") ? JSON.convertValue(p.path("type"), Object.class) : p.has("oneOf") ? "oneOf" : "any");
                if (required.contains(f.getKey())) {
                    line.put("required", true);
                }
                if (p.has("description")) {
                    line.put("description", p.path("description").asText());
                }
                if (p.has("enum")) {
                    line.put("enum", JSON.convertValue(p.path("enum"), List.class));
                }
                if (p.has("properties") || p.has("oneOf") || p.path("items").has("properties")) {
                    line.put("more", "describe_schema with part=<path>.<name> for its fields");
                }
                properties.put(f.getKey(), line);
            });
            summary.put("properties", properties);
        }
        if (node.has("oneOf")) {
            List<Object> variants = new ArrayList<>();
            node.path("oneOf").forEach(v -> variants.add(resolve(v, defs).path("required")));
            summary.put("oneOf", JSON.convertValue(variants, List.class));
            summary.put("more", "describe_schema with variant=<index> for one alternative");
        }
        if (node.has("items")) {
            summary.put("items", summarize(resolve(node.path("items"), defs), defs));
        }
        return summary;
    }

    // ---------------------------------------------------------------------- diagnostics

    private RuntimeConfig.Instance instance(Map<String, Object> args) {
        List<RuntimeConfig.Instance> all = config.allInstances();
        if (!manifest.multiInstance()) {
            return all.get(0);
        }
        Object name = args.get("instance");
        List<String> names = all.stream().map(RuntimeConfig.Instance::id).toList();
        if (name == null) {
            throw new ToolError("INSTANCE_REQUIRED", "instance is required", "One of: " + String.join(", ", names));
        }
        return all.stream().filter(i -> i.id().equals(name.toString())).findFirst()
                .orElseThrow(() -> new ToolError("INSTANCE_UNKNOWN", "No instance named '" + name + "'", "One of: " + String.join(", ", names)));
    }

    /**
     * The chain the instance's host presents (runtime-config §5.5). The handshake only reads the
     * certificates: nothing is sent and the connection is closed. Limited to configured instances.
     */
    private Object certificate(RuntimeConfig.Instance instance) {
        URI uri = BaseUrl.resolve(instance.baseUrl(), "");
        if (!uri.getScheme().equals("https")) {
            throw new ToolError("INPUT_VALIDATION", instance.id() + " uses plain HTTP; there is no certificate");
        }
        List<X509Certificate> chain = new ArrayList<>();
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new Capture(chain)}, null);
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
                try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), uri.getPort(), true)) {
                    SSLParameters parameters = socket.getSSLParameters();
                    if (!Character.isDigit(uri.getHost().charAt(0))) {
                        parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));
                    }
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                    socket.startHandshake();
                }
            }
        } catch (IOException | GeneralSecurityException e) {
            if (chain.isEmpty()) {
                throw new ToolError("UPSTREAM_UNREACHABLE", "Could not read the certificate of " + instance.id() + ": "
                        + e.getClass().getSimpleName());
            }
        }
        List<Map<String, Object>> certificates = new ArrayList<>();
        for (X509Certificate cert : chain) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("subject", cert.getSubjectX500Principal().getName());
            line.put("issuer", cert.getIssuerX500Principal().getName());
            line.put("notBefore", cert.getNotBefore().toInstant().toString());
            line.put("notAfter", cert.getNotAfter().toInstant().toString());
            line.put("sha256", fingerprint(cert));
            line.put("pem", pem(cert));
            certificates.add(line);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance.id());
        result.put("host", uri.getHost());
        result.put("chain", certificates);
        boolean covers = !chain.isEmpty() && covers(chain.get(0), uri.getHost());
        result.put("hostnameMatches", covers);
        if (!covers) {
            result.put("warning", "The certificate does not cover " + uri.getHost() + "; check the address before trusting it");
        }
        return result;
    }

    private Object connectivity(RuntimeConfig.Instance instance) {
        URI uri = BaseUrl.resolve(instance.baseUrl(), "");
        Map<String, Object> steps = new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instance", instance.id());
        result.put("address", uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort());
        result.put("steps", steps);
        try {
            steps.put("dns", InetAddress.getByName(uri.getHost()).getHostAddress());
        } catch (IOException e) {
            steps.put("dns", "failed: the host name does not resolve");
            result.put("ok", false);
            return result;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
            steps.put("tcp", "connected");
        } catch (IOException e) {
            steps.put("tcp", "failed: " + e.getClass().getSimpleName());
            result.put("ok", false);
            return result;
        }
        if (uri.getScheme().equals("https")) {
            try {
                SSLContext context = instance.tlsContext() != null ? instance.tlsContext().context() : SSLContext.getDefault();
                try (Socket raw = new Socket()) {
                    raw.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), CONNECT_TIMEOUT_MS);
                    try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), uri.getPort(), true)) {
                        SSLParameters parameters = socket.getSSLParameters();
                        if (instance.tlsContext() == null || !instance.tlsContext().skipHostname()) {
                            parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        }
                        socket.setSSLParameters(parameters);
                        socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                        socket.startHandshake();
                    }
                }
                steps.put("tls", "trusted");
            } catch (IOException | GeneralSecurityException e) {
                steps.put("tls", "failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                result.put("ok", false);
                result.put("hint", "Use tls_certificate_fetch to see the certificate the host presents");
                return result;
            }
        }
        result.put("ok", true);
        return result;
    }

    private static boolean covers(X509Certificate cert, String host) {
        try {
            Collection<List<?>> names = cert.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    String value = String.valueOf(name.get(1)).toLowerCase(Locale.ROOT);
                    String h = host.toLowerCase(Locale.ROOT);
                    if (value.equals(h)) {
                        return true;
                    }
                    // a wildcard covers exactly one label: *.example.com covers a.example.com, not a.b.example.com
                    if (value.startsWith("*.") && h.endsWith(value.substring(1))) {
                        String label = h.substring(0, h.length() - value.length() + 1);
                        if (!label.isEmpty() && !label.contains(".")) {
                            return true;
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            return false;
        }
        return false;
    }

    private static String fingerprint(X509Certificate cert) {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    private static String pem(X509Certificate cert) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (GeneralSecurityException e) {
            return "";
        }
    }

    /** Records the presented chain; used only to read certificates, never for a connection that carries data. */
    private record Capture(List<X509Certificate> chain) implements X509TrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] certificates, String authType) {
            chain.addAll(List.of(certificates));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certificates, String authType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
