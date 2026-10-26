package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth 2 client credentials (RFC 6749 §4.4, auth.md §2.1): one token per instance, cached until
 * {@code expires_in} minus 60 s; concurrent calls wait for one token request. The token endpoint is
 * reached with the instance's TLS settings.
 */
final class OAuthTokens {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration EARLY = Duration.ofSeconds(60);
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private record Token(String value, Instant refreshAt) {
    }

    private final Map<String, Token> tokens = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Redactor redactor;
    private final Clock clock;

    OAuthTokens(Redactor redactor, Clock clock) {
        this.redactor = redactor;
        this.clock = clock;
    }

    String token(RuntimeConfig.Instance instance, HttpClient client) {
        String key = key(instance);
        Token token = tokens.get(key);
        if (token != null && clock.instant().isBefore(token.refreshAt())) {
            return token.value();
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            token = tokens.get(key);
            if (token != null && clock.instant().isBefore(token.refreshAt())) {
                return token.value();
            }
            token = request(instance, client);
            tokens.put(key, token);
            return token.value();
        }
    }

    /** After a 401 from the target: the next call requests a new token. */
    void discard(RuntimeConfig.Instance instance) {
        tokens.remove(key(instance));
    }

    private Token request(RuntimeConfig.Instance instance, HttpClient client) {
        JsonNode auth = instance.authEntries().stream().filter(e -> e.path("type").asText().equals("oauth2ClientCredentials"))
                .findFirst().orElse(instance.auth());
        List<String> form = new ArrayList<>();
        form.add("grant_type=client_credentials");
        List<String> scopes = new ArrayList<>();
        auth.path("scopes").forEach(s -> scopes.add(s.asText()));
        if (!scopes.isEmpty()) {
            form.add("scope=" + encode(String.join(" ", scopes)));
        }
        if (auth.hasNonNull("audience")) {
            form.add("audience=" + encode(auth.get("audience").asText()));
            form.add("resource=" + encode(auth.get("audience").asText()));
        }
        HttpRequest.Builder http = HttpRequest.newBuilder(URI.create(auth.path("tokenUrl").asText()))
                .timeout(Duration.ofSeconds(instance.timeoutSeconds()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        String clientId = auth.path("clientId").asText();
        String clientSecret = auth.path("clientSecret").asText();
        if (auth.path("clientAuth").asText("basic").equals("post")) {
            form.add("client_id=" + encode(clientId));
            form.add("client_secret=" + encode(clientSecret));
        } else {
            // RFC 6749 §2.3.1: form-encode id and secret before Basic
            http.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8)));
        }
        http.POST(HttpRequest.BodyPublishers.ofString(String.join("&", form)));

        HttpResponse<String> response;
        try {
            response = client.send(http.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ToolError("AUTH_UPSTREAM", "token request for " + instance.id() + " failed: " + e.getClass().getSimpleName(),
                    "Check the instance's tokenUrl and network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolError("INTERNAL", "the call was interrupted");
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (IOException e) {
            body = JSON.createObjectNode();
        }
        if (response.statusCode() != 200 || !body.path("access_token").isTextual()) {
            String error = body.path("error").asText("HTTP " + response.statusCode());
            throw new ToolError("AUTH_UPSTREAM", "token request for " + instance.id() + " was refused: " + error,
                    "Check the instance's clientId, clientSecret and scopes");
        }
        String value = body.get("access_token").asText();
        redactor.add(value);
        long seconds = body.path("expires_in").asLong(DEFAULT_LIFETIME.toSeconds());
        Instant refreshAt = clock.instant().plusSeconds(seconds).minus(EARLY);
        return new Token(value, refreshAt);
    }

    private static String key(RuntimeConfig.Instance instance) {
        return instance.api() + "/" + instance.id();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
