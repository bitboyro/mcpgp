package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.runtime.mcp.Inflight;

import java.io.IOException;

/**
 * Cancellation over Streamable HTTP ({@code notifications/cancelled}). Runs after {@link InboundFilter}, so
 * only authenticated requests get here: a {@code tools/call} or {@code resources/read} is registered with
 * {@link Inflight} (and tagged for its handler) until the answer has been written; a cancel notification
 * stops the request it names, in the same session, and is answered with {@code 202} here.
 */
final class CancellationFilter extends HttpFilter {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!request.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }
        InboundFilter.CachedBody body = request instanceof InboundFilter.CachedBody cached ? cached : new InboundFilter.CachedBody(request);
        JsonNode message;
        try {
            message = JSON.readTree(body.bytes());
        } catch (IOException e) {
            chain.doFilter(body, response);          // not JSON: the transport answers it
            return;
        }
        String session = request.getHeader("Mcp-Session-Id");
        String scope = session == null ? "-" : session;
        if (Inflight.isCancel(message)) {
            Inflight.accept(scope, message);
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            return;
        }
        String key = Inflight.accept(scope, message);
        try {
            chain.doFilter(key == null ? body : new InboundFilter.CachedBody(request, JSON.writeValueAsBytes(message)), response);
        } finally {
            Inflight.end(key);
        }
    }
}
