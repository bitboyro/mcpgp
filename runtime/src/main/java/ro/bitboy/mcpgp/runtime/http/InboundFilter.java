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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guards the MCP endpoint (auth.md §1, runtime-config §4): the Origin check against DNS rebinding,
 * then inbound auth. In {@code static} mode a bearer key is compared in constant time with every
 * configured key; the matching key's name identifies the client in audit events.
 */
final class InboundFilter extends HttpFilter {

    /** Request attribute with the authenticated client's key name. */
    static final String CLIENT = "mcpgp.client";

    private record Key(String name, byte[] digest) {
    }

    private final RuntimeConfig.Server server;
    private final List<Key> keys = new ArrayList<>();
    private final AuditLog audit;

    InboundFilter(RuntimeConfig.Server server, AuditLog audit) {
        this.server = server;
        this.audit = audit;
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
        String session = request.getHeader("Mcp-Session-Id");
        chain.doFilter(request, response);
        // session.start when the server hands out a session id; session.end when the client ends it
        String issued = response.getHeader("Mcp-Session-Id");
        if (session == null && issued != null && response.getStatus() < 300) {
            sessionEvent("session.start", request);
        } else if (session != null && request.getMethod().equals("DELETE") && response.getStatus() < 300) {
            sessionEvent("session.end", request);
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
        response.getOutputStream().write(("{\"error\":\"" + (status == 401 ? "unauthorized" : "forbidden") + "\"}")
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
