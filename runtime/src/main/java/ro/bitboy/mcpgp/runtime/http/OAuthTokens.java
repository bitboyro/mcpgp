package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks the access tokens MCP clients present in {@code oauth} mode (auth.md §1.1). JWTs: the
 * signature with the issuer's JWKS (found through its RFC 8414 or OpenID metadata, cached, refreshed
 * when a key is unknown), an asymmetric algorithm, {@code iss}, {@code exp} / {@code nbf} with 60 s
 * skew, and an {@code aud} that names this server (RFC 8707). Other tokens: RFC 7662 introspection,
 * when it is configured. The token itself is never logged or kept, only its hash, for the
 * introspection cache.
 */
final class OAuthTokens {

    /** A valid token: who it belongs to, and its scopes. */
    record Grant(String subject, Set<String> scopes) {
    }

    /** The token is not valid here: a {@code 401 invalid_token}. */
    static final class Invalid extends Exception {
        Invalid(String reason) {
            super(reason);
        }
    }

    /** The authorization server can't be reached to check the token: a {@code 503}. */
    static final class Unavailable extends Exception {
        Unavailable(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<JWSAlgorithm> ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512, JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
            JWSAlgorithm.EdDSA, JWSAlgorithm.Ed25519);
    private static final Duration INTROSPECTION_CACHE = Duration.ofSeconds(60);

    private final String issuer;
    private final String resource;
    private final String jwksUri;
    private final JsonNode introspection;
    private final HttpClient http;
    private final Clock clock;
    private volatile DefaultJWTProcessor<SecurityContext> processor;
    private final Map<String, Cached> introspected = new ConcurrentHashMap<>();

    private record Cached(Grant grant, Instant until) {
    }

    /**
     * @param resource      this server's resource identifier ({@code publicUrl} + base path); tokens must name it in {@code aud}
     * @param introspection {@code server.auth.introspection} (secrets resolved), or a missing node
     */
    OAuthTokens(String issuer, String jwksUri, String resource, JsonNode introspection, HttpClient http, Clock clock) {
        this.issuer = issuer;
        this.jwksUri = jwksUri;
        this.resource = resource;
        this.introspection = introspection;
        this.http = http;
        this.clock = clock;
    }

    Grant check(String token) throws Invalid, Unavailable {
        if (token == null || token.isBlank()) {
            throw new Invalid("no token");
        }
        if (token.chars().filter(c -> c == '.').count() == 2) {
            return checkJwt(token);
        }
        if (introspection != null && !introspection.isMissingNode() && introspection.hasNonNull("url")) {
            return introspect(token);
        }
        throw new Invalid("not a JWT, and no introspection is configured");
    }

    private Grant checkJwt(String token) throws Invalid, Unavailable {
        JWTClaimsSet claims;
        try {
            claims = processor().process(token, null);
        } catch (com.nimbusds.jose.RemoteKeySourceException e) {
            throw new Unavailable("the issuer's keys can't be fetched", e);
        } catch (java.text.ParseException | com.nimbusds.jose.proc.BadJOSEException | com.nimbusds.jose.JOSEException e) {
            throw new Invalid(e.getMessage());
        }
        Set<String> scopes = new LinkedHashSet<>();
        Object scope = claims.getClaim("scope");
        if (scope instanceof String text) {
            scopes.addAll(List.of(text.trim().split("\\s+")));
        }
        Object scp = claims.getClaim("scp");
        if (scp instanceof List<?> list) {
            list.forEach(s -> scopes.add(String.valueOf(s)));
        } else if (scp instanceof String text) {
            scopes.addAll(List.of(text.trim().split("\\s+")));
        }
        scopes.remove("");
        String subject = claims.getSubject() != null ? claims.getSubject() : String.valueOf(claims.getClaim("client_id"));
        return new Grant(subject, scopes);
    }

    private DefaultJWTProcessor<SecurityContext> processor() throws Unavailable {
        DefaultJWTProcessor<SecurityContext> current = processor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (processor == null) {
                String keys = jwksUri != null ? jwksUri : discoverJwks();
                JWKSource<SecurityContext> source;
                try {
                    source = JWKSourceBuilder.create(URI.create(keys).toURL()).retrying(true).build();
                } catch (java.net.MalformedURLException e) {
                    throw new Unavailable("the issuer's jwks_uri is not a URL: " + keys, e);
                }
                DefaultJWTProcessor<SecurityContext> p = new DefaultJWTProcessor<>();
                p.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
                p.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHMS, source));
                DefaultJWTClaimsVerifier<SecurityContext> verifier = new DefaultJWTClaimsVerifier<>(Set.of(resource),
                        new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("exp", "iss", "aud"), null);
                verifier.setMaxClockSkew(60);
                p.setJWTClaimsSetVerifier(verifier);
                processor = p;
            }
            return processor;
        }
    }

    /** RFC 8414 metadata first ({@code /.well-known/oauth-authorization-server}, path appended), then OpenID's. */
    private String discoverJwks() throws Unavailable {
        URI base = URI.create(issuer);
        String path = base.getPath() == null || base.getPath().equals("/") ? "" : base.getPath().replaceAll("/+$", "");
        String origin = base.getScheme() + "://" + base.getRawAuthority();
        List<String> candidates = List.of(origin + "/.well-known/oauth-authorization-server" + path,
                issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration");
        Exception last = null;
        for (String candidate : candidates) {
            try {
                HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(candidate)).timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json").build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    continue;
                }
                JsonNode metadata = JSON.readTree(response.body());
                if (!issuer.equals(metadata.path("issuer").asText())) {
                    throw new Unavailable("the metadata at " + candidate + " is for another issuer (" + metadata.path("issuer").asText() + ")", null);
                }
                if (metadata.hasNonNull("jwks_uri")) {
                    return metadata.get("jwks_uri").asText();
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Unavailable("interrupted", e);
            }
        }
        throw new Unavailable("no jwks_uri in the issuer's metadata (" + issuer + ")", last);
    }

    private Grant introspect(String token) throws Invalid, Unavailable {
        String key = sha256(token);
        Instant now = clock.instant();
        Cached cached = introspected.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.grant();
        }
        String form = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&token_type_hint=access_token";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(introspection.path("url").asText())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (introspection.hasNonNull("clientId")) {
            String credentials = URLEncoder.encode(introspection.path("clientId").asText(), StandardCharsets.UTF_8) + ":"
                    + URLEncoder.encode(introspection.path("clientSecret").asText(""), StandardCharsets.UTF_8);
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        JsonNode answer;
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new Unavailable("introspection answered " + response.statusCode(), null);
            }
            answer = JSON.readTree(response.body());
        } catch (IOException e) {
            throw new Unavailable("introspection failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("interrupted", e);
        }
        if (!answer.path("active").asBoolean(false)) {
            throw new Invalid("the token is not active");
        }
        if (answer.has("iss") && !issuer.equals(answer.path("iss").asText())) {
            throw new Invalid("the token is from another issuer");
        }
        JsonNode aud = answer.path("aud");
        if (!aud.isMissingNode()) {
            boolean ours = aud.isArray() ? java.util.stream.StreamSupport.stream(aud.spliterator(), false).anyMatch(a -> resource.equals(a.asText()))
                    : resource.equals(aud.asText());
            if (!ours) {
                throw new Invalid("the token is for another resource");
            }
        }
        Instant expires = answer.has("exp") ? Instant.ofEpochSecond(answer.path("exp").asLong()) : now.plus(INTROSPECTION_CACHE);
        if (!expires.isAfter(now)) {
            throw new Invalid("the token has expired");
        }
        Set<String> scopes = new LinkedHashSet<>();
        if (answer.hasNonNull("scope")) {
            scopes.addAll(List.of(answer.path("scope").asText().trim().split("\\s+")));
            scopes.remove("");
        }
        String subject = answer.hasNonNull("sub") ? answer.path("sub").asText() : answer.path("client_id").asText("unknown");
        Grant grant = new Grant(subject, scopes);
        Instant until = now.plus(INTROSPECTION_CACHE);
        introspected.put(key, new Cached(grant, until.isBefore(expires) ? until : expires));
        if (introspected.size() > 10_000) {
            introspected.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        }
        return grant;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
