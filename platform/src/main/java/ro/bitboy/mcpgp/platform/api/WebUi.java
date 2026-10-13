package ro.bitboy.mcpgp.platform.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The web UI (M6): a single page built from {@code platform/web} into the jar's {@code static/}. Its
 * routes all answer with {@code index.html}; the hashed files under {@code /assets/} are served by
 * Spring's static resources. The page talks to the same {@code /api/v1} as everyone else.
 */
@Controller
public class WebUi {

    private static final byte[] MISSING = """
            <!doctype html><meta charset="utf-8"><title>mcpgp platform</title>
            <p>The web UI is not part of this build (it was built with -Dskip.npm). The API is at /api/v1.</p>
            """.getBytes(StandardCharsets.UTF_8);

    private final byte[] index;

    public WebUi() {
        try (InputStream in = WebUi.class.getResourceAsStream("/static/index.html")) {
            index = in == null ? MISSING : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping({"/", "/sign-in", "/library", "/library/**", "/activity", "/builds", "/agents", "/account", "/servers/**"})
    public ResponseEntity<byte[]> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).contentType(MediaType.TEXT_HTML).body(index);
    }

    /**
     * Browser hardening for every answer: only the platform's own scripts, styles, fonts and API; no
     * framing; no referrer to other sites.
     */
    public static final class Headers extends HttpFilter {

        static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
                + "connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

        @Override
        protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
            response.setHeader("Content-Security-Policy", POLICY);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Referrer-Policy", "no-referrer");
            response.setHeader("X-Frame-Options", "DENY");
            if (request.getRequestURI().startsWith("/assets/")) {
                // file names carry their content hash
                response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
            }
            chain.doFilter(request, response);
        }
    }
}
