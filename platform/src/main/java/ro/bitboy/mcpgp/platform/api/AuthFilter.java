package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.platform.service.Access;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@code /api/v1} request needs a bearer token (spec/platform-api.md §3, §4), except the API's
 * own OpenAPI document. The principal goes into the request for the controllers.
 */
public final class AuthFilter extends HttpFilter {

    public static final String PRINCIPAL = "mcpgp.principal";

    private final Access access;

    public AuthFilter(Access access) {
        this.access = access;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getRequestURI().equals("/api/v1/openapi.json")) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Access.Principal> principal = access.authenticate(request.getHeader("Authorization"));
        if (principal.isEmpty()) {
            var problem = Errors.problem(401, "UNAUTHORIZED", "a valid bearer token is required", Map.of());
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer realm=\"mcpgp\"");
            response.setContentType("application/problem+json");
            response.getOutputStream().write(problem.getBody());
            return;
        }
        request.setAttribute(PRINCIPAL, principal.get());
        chain.doFilter(request, response);
    }
}
