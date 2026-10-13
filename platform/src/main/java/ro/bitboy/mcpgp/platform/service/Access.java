package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Identities, tokens and scopes (spec/platform-api.md §3). A token is {@code mcpgp_<id>_<secret>}; only the
 * SHA-256 of the secret is stored and compared in constant time. A token's scopes are at most its
 * identity's scopes.
 */
public final class Access {

    public static final List<String> SCOPES = List.of("servers:read", "servers:write", "builds:run", "builds:release",
            "builds:delete", "library:write", "approvals:decide", "admin");
    public static final Map<String, Set<String>> ROLES = Map.of(
            "viewer", Set.of("servers:read"),
            "builder", Set.of("servers:read", "servers:write", "builds:run", "library:write"),
            "releaser", Set.of("servers:read", "servers:write", "builds:run", "library:write", "builds:release", "builds:delete",
                    "approvals:decide"),
            "admin", Set.copyOf(SCOPES));
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Who is calling, and what this call may do. */
    public record Principal(Model.Identity identity, Set<String> scopes, String token) {
        public boolean has(String scope) {
            return scopes.contains(scope) || scopes.contains("admin");
        }

        public void require(String scope) {
            if (!has(scope)) {
                throw ApiException.forbidden(scope);
            }
        }
    }

    /** A new token: its record, and the value shown once. */
    public record IssuedToken(Model.Token record, String value) {
    }

    private final Store store;
    private final Map<String, Instant> lastUse = new java.util.concurrent.ConcurrentHashMap<>();

    public Access(Store store) {
        this.store = store;
    }

    public Model.Identity createIdentity(String kind, String name, Set<String> scopes, String owner) {
        if (!Set.of("person", "agent", "pipeline").contains(kind)) {
            throw ApiException.validation("kind must be person, agent or pipeline");
        }
        if (!SCOPES.containsAll(scopes)) {
            throw ApiException.validation("unknown scopes; known: " + String.join(", ", SCOPES));
        }
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,79}")) {
            throw ApiException.validation("name: letters, digits and . _ @ -, at most 80");
        }
        if (store.identities().stream().anyMatch(i -> i.name().equalsIgnoreCase(name))) {
            throw new ApiException(409, "CONFLICT", "an identity named " + name + " exists");
        }
        Model.Identity identity = new Model.Identity("id_" + random(9), kind, name, new LinkedHashSet<>(scopes), owner,
                Instant.now().toString(), false);
        store.saveIdentity(identity);
        return identity;
    }

    public IssuedToken issue(Model.Identity identity, Set<String> scopes, String label, Instant expires) {
        Set<String> granted = scopes == null || scopes.isEmpty() ? identity.scopes() : scopes;
        if (!identity.scopes().contains("admin") && !identity.scopes().containsAll(granted)) {
            throw ApiException.validation("a token can't have scopes its identity doesn't have");
        }
        String id = random(9);
        String secret = random(24);
        Model.Token token = new Model.Token(id, identity.id(), hash(secret), new LinkedHashSet<>(granted), label,
                Instant.now().toString(), expires == null ? null : expires.toString(), false);
        store.saveToken(token);
        return new IssuedToken(token, "mcpgp_" + id + "_" + secret);
    }

    /** The principal of an {@code Authorization: Bearer} header, or empty. */
    public Optional<Principal> authenticate(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String value = authorization.substring(7).strip();
        String[] parts = value.split("_", 3);
        if (parts.length != 3 || !parts[0].equals("mcpgp")) {
            return Optional.empty();
        }
        Optional<Model.Token> token = store.tokens().stream().filter(t -> t.id().equals(parts[1])).findFirst();
        // compare even when there is no such token, so timing doesn't tell
        byte[] presented = hash(parts[2]).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = token.map(t -> t.hash().getBytes(StandardCharsets.US_ASCII)).orElse(new byte[presented.length]);
        if (!MessageDigest.isEqual(presented, expected) || token.isEmpty()) {
            return Optional.empty();
        }
        Model.Token t = token.get();
        if (t.revoked() || t.expires() != null && Instant.parse(t.expires()).isBefore(Instant.now())) {
            return Optional.empty();
        }
        Optional<Principal> principal = store.identities().stream().filter(i -> i.id().equals(t.identity()) && !i.disabled()).findFirst()
                .map(identity -> new Principal(identity, t.scopes(), t.id()));
        principal.ifPresent(p -> used(t.id()));
        return principal;
    }

    /** Records a token's use, at most once a minute per token (and per platform instance). */
    private void used(String token) {
        Instant now = Instant.now();
        Instant last = lastUse.get(token);
        if (last != null && last.isAfter(now.minusSeconds(60))) {
            return;
        }
        lastUse.put(token, now);
        store.saveTokenUse(new Model.TokenUse(token, now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()));
    }

    static String hash(String secret) {
        return FileStore.sha256(secret.getBytes(StandardCharsets.UTF_8));
    }

    private static String random(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value).replace('_', 'x').replace('-', 'y');
    }
}
