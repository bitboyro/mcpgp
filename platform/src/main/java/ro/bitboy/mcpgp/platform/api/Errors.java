package ro.bitboy.mcpgp.platform.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every error as {@code application/problem+json} (RFC 9457) with a stable {@code code}; no stack traces. */
@RestControllerAdvice
public class Errors {

    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<byte[]> api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<byte[]> notFound(NoResourceFoundException e) {
        return problem(404, "NOT_FOUND", "no such endpoint", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> unexpected(Exception e) {
        LOG.error("Request failed", e);
        return problem(500, "INTERNAL", "the request failed; see the platform log", Map.of());
    }

    static ResponseEntity<byte[]> problem(int status, String code, String detail, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("status", status);
        body.put("code", code);
        body.put("detail", detail);
        body.putAll(details);
        ResponseEntity<byte[]> json = Json.status(status, body);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(json.getBody());
    }
}
