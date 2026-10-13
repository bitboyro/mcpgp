package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ro.bitboy.mcpgp.platform.service.Access;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every {@code /api/v1} request needs a bearer token (spec/platform-api.md §3, §4), except the API's
 * own OpenAPI document, sign-in and the sign-in options. The web UI's session travels in an HttpOnly
 * cookie instead; requests that change something with that cookie must carry the web client's header
 * and, when the browser sends one, an {@code Origin} of this host (cross-site requests can't do either
 * without a CORS preflight, which the platform never grants). The principal goes into the request for
 * the controllers.
 */
public final class AuthFilter extends HttpFilter {

    public static final String PRINCIPAL = "mcpgp.principal";
    /** The web UI's session cookie. */
    public static final String COOKIE = "mcpgp_session";
    /** The web UI sends {@code X-Mcpgp-Client: web} on every request. */
    public static final String CLIENT_HEADER = "X-Mcpgp-Client";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final Access access;
    /** Whether a person has to replace a temporary password first; null: never (tests). */
    private final java.util.function.Predicate<String> mustChange;

    public AuthFilter(Access access) {
        this(access, null);
    }

    public AuthFilter(Access access, java.util.function.Predicate<String> mustChange) {
        this.access = access;
        this.mustChange = mustChange;
    }

    /** What a person with a temporary password may do: see who they are, choose a new password, sign out. */
    private static boolean allowedBeforeChange(HttpServletRequest request, Access.Principal who) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        return method.equals("GET") && (uri.equals("/api/v1/me") || uri.equals("/api/v1/settings"))
                || method.equals("PUT") && uri.equals("/api/v1/identities/" + who.identity().id() + "/password")
                || method.equals("DELETE") && uri.equals("/api/v1/sessions/current");
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String uri = request.getRequestURI();
        if (uri.equals("/api/v1/openapi.json") || uri.equals("/api/v1/platform") && request.getMethod().equals("GET")
                || uri.equals("/api/v1/sessions") && request.getMethod().equals("POST")) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        boolean viaCookie = false;
        if (authorization == null) {
            String session = cookie(request);
            if (session != null) {
                authorization = "Bearer " + session;
                viaCookie = true;
            }
        }
        if (viaCookie && !SAFE.contains(request.getMethod()) && !(isWeb(request) && sameOrigin(request))) {
            fail(response, 403, "FORBIDDEN", "a browser session can only change things from the platform's own pages");
            return;
        }
        Optional<Access.Principal> principal = access.authenticate(authorization);
        if (principal.isEmpty()) {
            if (!viaCookie) {
                response.setHeader("WWW-Authenticate", "Bearer realm=\"mcpgp\"");
            }
            fail(response, 401, "UNAUTHORIZED", "a valid bearer token is required");
            return;
        }
        if (mustChange != null && principal.get().identity().kind().equals("person") && mustChange.test(principal.get().identity().id())
                && !allowedBeforeChange(request, principal.get())) {
            fail(response, 403, "PASSWORD_CHANGE_REQUIRED", "choose a new password first: this one was set as temporary");
            return;
        }
        request.setAttribute(PRINCIPAL, principal.get());
        chain.doFilter(request, response);
    }

    static boolean isWeb(HttpServletRequest request) {
        return "web".equals(request.getHeader(CLIENT_HEADER));
    }

    /** No {@code Origin} (same-origin GETs, older browsers), or one naming the host the request was sent to. */
    static boolean sameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            String authority = URI.create(origin).getAuthority();
            return authority != null && authority.equalsIgnoreCase(request.getHeader("Host"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookie.getName().equals(COOKIE) && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static void fail(HttpServletResponse response, int status, String code, String detail) throws IOException {
        var problem = Errors.problem(status, code, detail, Map.of());
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(problem.getBody());
    }
}
