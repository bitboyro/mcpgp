package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.runtime.audit.AuditLog;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.mcp.Ids;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guards the MCP endpoint (auth.md §1, runtime-config §4): the Origin check against DNS rebinding,
 * then inbound auth. In {@code static} mode a bearer key is compared in constant time with every
 * configured key; the matching key's name identifies the client in audit events. In {@code oauth}
 * mode the server is an OAuth resource server: tokens are checked by {@link OAuthTokens}, scopes by
 * {@link ScopePolicy}; the token's subject identifies the client.
 */
final class InboundFilter extends HttpFilter {

    /** Request attribute with the authenticated client's key name. */
    static final String CLIENT = "mcpgp.client";

    private record Key(String name, byte[] digest) {
    }

    /** Request attribute with the token's scopes ({@code oauth} mode). */
    static final String SCOPES = "mcpgp.scopes";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final RuntimeConfig.Server server;
    private final List<Key> keys = new ArrayList<>();
    private final AuditLog audit;
    private final OAuthTokens tokens;
    private final ScopePolicy policy;
    private final java.util.function.Supplier<ro.bitboy.mcpgp.runtime.mcp.ToolCatalog> catalog;

    private final ro.bitboy.mcpgp.runtime.mcp.Toolsets toolsets;

    InboundFilter(RuntimeConfig.Server server, AuditLog audit) {
        this(server, audit, null, () -> null, null);
    }

    /**
     * @param tokens   the token check in {@code oauth} mode (null otherwise)
     * @param catalog  the current tools, for scopes and per-session lists
     * @param toolsets {@code toolsets} exposure: each session's tool list (null otherwise)
     */
    InboundFilter(RuntimeConfig.Server server, AuditLog audit, OAuthTokens tokens,
                  java.util.function.Supplier<ro.bitboy.mcpgp.runtime.mcp.ToolCatalog> catalog, ro.bitboy.mcpgp.runtime.mcp.Toolsets toolsets) {
        this.toolsets = toolsets;
        this.server = server;
        this.audit = audit;
        this.tokens = tokens;
        this.catalog = catalog;
        this.policy = new ScopePolicy(server.auth().path("scopes"));
        for (JsonNode key : server.auth().path("keys")) {
            keys.add(new Key(key.path("name").asText(), digest(key.path("value").asText())));
        }
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String origin = request.getHeader("Origin");
        if (origin != null && !server.allowedOrigins().contains(origin)) {
            deny(response, 403, null, "origin " + origin + " is not allowed", request);
            return;
        }
        if (server.authMode().equals("static")) {
            String header = request.getHeader("Authorization");
            if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                deny(response, 401, "Bearer realm=\"mcp\"", "no bearer key", request);
                return;
            }
            String name = match(header.substring(7).strip());
            if (name == null) {
                deny(response, 401, "Bearer realm=\"mcp\", error=\"invalid_token\"", "unknown key", request);
                return;
            }
            request.setAttribute(CLIENT, name);
        }
        if (server.authMode().equals("oauth")) {
            request = oauth(request, response);
            if (request == null) {
                return;
            }
        } else if (toolsets != null && request.getMethod().equals("POST")) {
            request = toolList(request, response, null);
            if (request == null) {
                return;
            }
        }
        String session = request.getHeader("Mcp-Session-Id");
        chain.doFilter(request, response);
        // session.start when the server hands out a session id; session.end when the client ends it
        String issued = response.getHeader("Mcp-Session-Id");
        if (session == null && issued != null && response.getStatus() < 300) {
            sessionEvent("session.start", request);
        } else if (session != null && request.getMethod().equals("DELETE") && response.getStatus() < 300) {
            sessionEvent("session.end", request);
            if (toolsets != null) {
                toolsets.forget(session);
            }
        }
    }

    /**
     * {@code oauth} mode (auth.md §1.1): the token, then the scopes for {@code tools/call} (403
     * {@code insufficient_scope}) and {@code tools/list} (answered here with the allowed tools).
     *
     * @return the request to go on with, or null when this answered it
     */
    private HttpServletRequest oauth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String metadata = resourceMetadata(server);
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            deny(response, 401, "Bearer resource_metadata=\"" + metadata + "\"", "no bearer token", request);
            return null;
        }
        OAuthTokens.Grant grant;
        try {
            grant = tokens.check(header.substring(7).strip());
        } catch (OAuthTokens.Invalid e) {
            deny(response, 401, "Bearer error=\"invalid_token\", resource_metadata=\"" + metadata + "\"", "invalid token: " + e.getMessage(), request);
            return null;
        } catch (OAuthTokens.Unavailable e) {
            org.slf4j.LoggerFactory.getLogger(InboundFilter.class).warn("Can't check tokens: {}", e.getMessage());
            deny(response, 503, null, "the authorization server can't be reached", request);
            return null;
        }
        request.setAttribute(CLIENT, grant.subject());
        request.setAttribute(SCOPES, grant.scopes());
        if (!(policy.restricts() || toolsets != null) || !request.getMethod().equals("POST")) {
            return request;
        }
        CachedBody body = new CachedBody(request);
        JsonNode message;
        try {
            message = JSON.readTree(body.bytes());
        } catch (IOException e) {
            return body;            // not JSON: the transport answers it
        }
        if (message == null || !message.isObject()) {
            return body;
        }
        String method = message.path("method").asText();
        ro.bitboy.mcpgp.runtime.mcp.ToolCatalog tools = catalog.get();
        if (method.equals("tools/call") && tools != null) {
            String name = message.at("/params/name").asText();
            String target = name.equals("call_tool") ? message.at("/params/arguments/name").asText(name) : name;
            JsonNode tool = tools.tool(target).orElse(null);
            if (tool != null && !policy.allows(tool, grant.scopes())) {
                String needed = String.join(" ", policy.needed(tool));
                deny(response, 403, "Bearer error=\"insufficient_scope\", scope=\"" + needed + "\", resource_metadata=\"" + metadata + "\"",
                        "scope for " + target, request);
                return null;
            }
        } else if (method.equals("tools/list") && tools != null && message.has("id")) {
            answerToolList(request, response, message, tools, grant.scopes());
            return null;
        }
        return body;
    }

    /** Without OAuth, in toolsets mode: {@code tools/list} answered per session; other requests go on. */
    private HttpServletRequest toolList(HttpServletRequest request, HttpServletResponse response, Set<String> scopes) throws IOException {
        CachedBody body = new CachedBody(request);
        JsonNode message;
        try {
            message = JSON.readTree(body.bytes());
        } catch (IOException e) {
            return body;
        }
        ro.bitboy.mcpgp.runtime.mcp.ToolCatalog tools = catalog.get();
        if (message != null && message.isObject() && message.path("method").asText().equals("tools/list") && message.has("id") && tools != null) {
            answerToolList(request, response, message, tools, scopes);
            return null;
        }
        return body;
    }

    /**
     * {@code tools/list} for this request's session: the listed tools, plus (toolsets mode) the tools of the
     * session's enabled toolsets, less (oauth) what the token's scopes don't allow.
     */
    private void answerToolList(HttpServletRequest request, HttpServletResponse response, JsonNode message,
                                ro.bitboy.mcpgp.runtime.mcp.ToolCatalog tools, Set<String> scopes) throws IOException {
        List<JsonNode> candidates = new ArrayList<>(tools.listed());
        if (toolsets != null) {
            for (String name : toolsets.visible(request.getHeader("Mcp-Session-Id"))) {
                tools.tool(name).ifPresent(candidates::add);
            }
        }
        List<io.modelcontextprotocol.spec.McpSchema.Tool> allowed = new ArrayList<>();
        for (JsonNode tool : candidates) {
            if (scopes == null || policy.allows(tool, scopes)) {
                allowed.add(ro.bitboy.mcpgp.runtime.mcp.ToolCatalog.toMcp(tool));
            }
        }
        ObjectNode answer = JSON.createObjectNode().put("jsonrpc", "2.0");
        answer.set("id", message.get("id"));
        answer.putObject("result").set("tools", JSON.valueToTree(allowed));
        response.setStatus(200);
        response.setContentType("application/json");
        response.getOutputStream().write(JSON.writeValueAsBytes(answer));
    }

    /** RFC 9728 §3: {@code /.well-known/oauth-protected-resource} inserted before the resource's path. */
    static String resourceMetadata(RuntimeConfig.Server server) {
        String base = server.publicUrl().replaceAll("/+$", "");
        URI uri = URI.create(base);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = (uri.getRawPath() == null ? "" : uri.getRawPath()) + server.basePath();
        return origin + "/.well-known/oauth-protected-resource" + path;
    }

    /** The resource this server is (RFC 8707): tokens must name it in their audience. */
    static String resource(RuntimeConfig.Server server) {
        return server.publicUrl().replaceAll("/+$", "") + server.basePath();
    }

    /** A request whose body was read here, so the transport can read it again. */
    static final class CachedBody extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;

        CachedBody(HttpServletRequest request) throws IOException {
            super(request);
            this.body = request.getInputStream().readAllBytes();
        }

        byte[] bytes() {
            return body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private void sessionEvent(String type, HttpServletRequest request) {
        ObjectNode event = audit.event(type, "ok", Ids.correlation());
        ObjectNode actor = event.putObject("actor").put("kind", "client");
        Object client = request.getAttribute(CLIENT);
        if (client != null) {
            actor.put("id", client.toString()).put("auth", server.authMode());
        }
        audit.write(event);
    }

    /** Compares against every key, so the time doesn't depend on which one (or none) matches. */
    private String match(String presented) {
        byte[] candidate = digest(presented);
        String found = null;
        for (Key key : keys) {
            if (MessageDigest.isEqual(candidate, key.digest()) && found == null) {
                found = key.name();
            }
        }
        return found;
    }

    private void deny(HttpServletResponse response, int status, String challenge, String reason, HttpServletRequest request)
            throws IOException {
        ObjectNode event = audit.event("auth.inbound", "denied", Ids.correlation());
        event.putObject("actor").put("kind", "client").put("auth", server.authMode());
        event.put("code", "AUTH_INBOUND");
        audit.write(event);
        if (challenge != null) {
            response.setHeader("WWW-Authenticate", challenge);
        }
        response.setStatus(status);
        response.setContentType("application/json");
        String error = switch (status) {
            case 401 -> "unauthorized";
            case 403 -> challenge != null && challenge.contains("insufficient_scope") ? "insufficient_scope" : "forbidden";
            default -> "unavailable";
        };
        response.getOutputStream().write(("{\"error\":\"" + error + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        org.slf4j.LoggerFactory.getLogger(InboundFilter.class).info("Refused {} {} from {}: {}",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr(), reason.toLowerCase(Locale.ROOT));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
