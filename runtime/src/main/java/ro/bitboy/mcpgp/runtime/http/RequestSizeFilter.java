package ro.bitboy.mcpgp.runtime.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Refuses an MCP request body over the input limits (safety.inputLimits) with {@code 413}: by its
 * Content-Length, or, for a body without one, once reading passes the limit. Nothing larger is read.
 */
final class RequestSizeFilter extends HttpFilter {

    private final long limit;

    RequestSizeFilter(long limit) {
        this.limit = limit;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (limit == Long.MAX_VALUE || !"POST".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            refuse(response, declared);
            return;
        }
        if (declared >= 0) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
        }
        if (body.length > limit) {
            refuse(response, -1);
            return;
        }
        chain.doFilter(new InboundFilter.CachedBody(request, body), response);
    }

    private void refuse(HttpServletResponse response, long size) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        String message = "The request is " + (size >= 0 ? size / 1024 + " KB" : "too large") + "; the limit is " + limit / 1024
                + " KB (safety.inputLimits). Send less at once.";
        response.getOutputStream().write(("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"" + message + "\"}}")
                .getBytes(StandardCharsets.UTF_8));
    }
}
