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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-user identity by token exchange (auth.md §2.5): the signed-in user's access token, which the
 * server checked when the request came in, is exchanged at the identity provider for a token issued
 * for the API, which the call sends. RFC 8693, or Microsoft Entra's on-behalf-of flow ({@code flavor:
 * entra-obo}). The user's token goes only to the token endpoint; exchanged tokens are kept in memory,
 * per user and instance, until {@code expires_in} minus 60 s, and never written anywhere.
 */
final class TokenExchange {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration EARLY = Duration.ofSeconds(60);
    private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);
    /** Exchanged tokens kept: one per user and instance, the least recently used go first. */
    private static final int KEPT = 1000;
    static final String TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
    static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private record Token(String value, Instant refreshAt) {
    }

    private final Map<String, Token> tokens = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Token> eldest) {
            return size() > KEPT;
        }
    };
    private final Clock clock;

    TokenExchange(Clock clock) {
        this.clock = clock;
    }

    /** A token for the API on behalf of the user whose access token is {@code subject}. */
    String token(RuntimeConfig.Instance instance, JsonNode entry, String subject, HttpClient client) {
        String key = key(instance, subject);
        synchronized (tokens) {
            Token token = tokens.get(key);
            if (token != null && clock.instant().isBefore(token.refreshAt())) {
                Redactor.maskInCall(token.value());
                return token.value();
            }
        }
        Token token = request(instance, entry, subject, client);
        synchronized (tokens) {
            tokens.put(key, token);
        }
        return token.value();
    }

    /** After a 401 from the target: the user's next call exchanges again. */
    void discard(RuntimeConfig.Instance instance, String subject) {
        synchronized (tokens) {
            tokens.remove(key(instance, subject));
        }
    }

    private Token request(RuntimeConfig.Instance instance, JsonNode auth, String subject, HttpClient client) {
        List<String> form = new ArrayList<>();
        List<String> scopes = new ArrayList<>();
        auth.path("scopes").forEach(s -> scopes.add(s.asText()));
        if (auth.path("flavor").asText("rfc8693").equals("entra-obo")) {
            form.add("grant_type=" + encode(JWT_BEARER));
            form.add("assertion=" + encode(subject));
            form.add("requested_token_use=on_behalf_of");
        } else {
            form.add("grant_type=" + encode(TOKEN_EXCHANGE));
            form.add("subject_token=" + encode(subject));
            form.add("subject_token_type=" + encode(ACCESS_TOKEN));
            form.add("requested_token_type=" + encode(ACCESS_TOKEN));
            if (auth.hasNonNull("audience")) {
                form.add("audience=" + encode(auth.get("audience").asText()));
            }
            if (auth.hasNonNull("resource")) {
                form.add("resource=" + encode(auth.get("resource").asText()));
            }
        }
        if (!scopes.isEmpty()) {
            form.add("scope=" + encode(String.join(" ", scopes)));
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
            http.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8)));
        }
        http.POST(HttpRequest.BodyPublishers.ofString(String.join("&", form)));

        HttpResponse<String> response;
        try {
            response = client.send(http.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ToolError("AUTH_DOWNSTREAM", "token exchange for " + instance.id() + " failed: " + e.getClass().getSimpleName(),
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
            throw new ToolError("AUTH_DOWNSTREAM", "the identity provider didn't exchange your sign-in for " + instance.id() + ": " + error,
                    "invalid_grant usually means your sign-in isn't accepted for this API; ask whoever runs this server");
        }
        String value = body.get("access_token").asText();
        Redactor.maskInCall(value);
        long seconds = body.path("expires_in").asLong(DEFAULT_LIFETIME.toSeconds());
        return new Token(value, clock.instant().plusSeconds(seconds).minus(EARLY));
    }

    /** The instance and a hash of the user's token: the token itself isn't kept as a key. */
    private static String key(RuntimeConfig.Instance instance, String subject) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8));
            return instance.api() + "/" + instance.id() + "/" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}
