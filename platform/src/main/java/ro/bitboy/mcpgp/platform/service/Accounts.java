package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Local accounts (spec/platform-api.md §3): passwords as PBKDF2-HMAC-SHA256 (600 000 iterations,
 * 16-byte salt), at least 12 characters. Five failures in a row lock the account for 15 minutes. An
 * unknown name and a wrong password get the same answer and the same work, so neither tells which.
 * A sign-in gives a session token valid 12 hours.
 */
public final class Accounts {

    static final int ITERATIONS = 600_000;
    static final int MIN_LENGTH = 12;
    static final int MAX_FAILURES = 5;
    static final Duration LOCK = Duration.ofMinutes(15);
    public static final Duration SESSION = Duration.ofHours(12);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Hashed when there is no such account, so the answer takes the same time. */
    private static final byte[] DUMMY_SALT = new byte[16];

    private final Store store;
    private final Access access;
    private final Clock clock;

    private final boolean local;
    private final Directory directory;
    private final Map<String, String> directoryRoles;
    private final SignIns signIns;
    /** Failed directory sign-ins per name: [failures, locked until (epoch ms)]. */
    private final Map<String, long[]> directoryFailures = new java.util.concurrent.ConcurrentHashMap<>();

    public Accounts(Store store, Access access, Clock clock) {
        this(store, access, clock, true, null, Map.of());
    }

    /** With a company directory: names without a local account are checked there (spec/platform-api.md §3). */
    public Accounts(Store store, Access access, Clock clock, boolean local, Directory directory, Map<String, String> directoryRoles) {
        this.store = store;
        this.access = access;
        this.clock = clock;
        this.local = local;
        this.directory = directory;
        this.directoryRoles = directoryRoles;
        this.signIns = new SignIns(store, access, clock);
    }

    public void setPassword(Model.Identity identity, String password) {
        setPassword(identity, password, false);
    }

    /** @param mustChange a temporary password: the person chooses a new one at the next sign-in */
    public void setPassword(Model.Identity identity, String password, boolean mustChange) {
        if (!identity.kind().equals("person")) {
            throw ApiException.validation("only people have passwords; agents and pipelines use tokens");
        }
        if (password == null || password.length() < MIN_LENGTH) {
            throw ApiException.validation("the password needs at least " + MIN_LENGTH + " characters");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        store.saveCredential(new Model.Credential(identity.id(), Base64.getEncoder().encodeToString(salt), ITERATIONS,
                Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS)), 0, null, mustChange));
    }

    private Optional<Model.Credential> credential(String identity) {
        return store.credentials().stream().filter(c -> c.identity().equals(identity)).findFirst();
    }

    /** Whether the person has to choose a new password before doing anything else. */
    public boolean mustChange(String identity) {
        return credential(identity).map(Model.Credential::mustChange).orElse(false);
    }

    /** Whether wrong passwords locked the person's account (local or directory sign-in), and until when. */
    public Optional<Instant> lockedUntil(Model.Identity identity) {
        Instant now = clock.instant();
        Optional<Instant> local = credential(identity.id()).map(Model.Credential::lockedUntil).map(Instant::parse).filter(t -> t.isAfter(now));
        if (local.isPresent()) {
            return local;
        }
        long[] failures = directoryFailures.get(identity.name().toLowerCase(java.util.Locale.ROOT));
        return failures != null && failures[1] > now.toEpochMilli() ? Optional.of(Instant.ofEpochMilli(failures[1])) : Optional.empty();
    }

    /** Ends a lock after wrong passwords (an admin's Unlock); the count of wrong passwords starts again. */
    public synchronized void unlock(Model.Identity identity) {
        credential(identity.id()).ifPresent(c -> store.saveCredential(
                new Model.Credential(c.identity(), c.salt(), c.iterations(), c.hash(), 0, null, c.mustChange())));
        directoryFailures.remove(identity.name().toLowerCase(java.util.Locale.ROOT));
    }

    public boolean hasPassword(Model.Identity identity) {
        return store.credentials().stream().anyMatch(c -> c.identity().equals(identity.id()));
    }

    /** Checks a person's current password (for changing it). */
    public boolean check(Model.Identity identity, String password) {
        return store.credentials().stream().filter(c -> c.identity().equals(identity.id())).findFirst()
                .map(c -> MessageDigest.isEqual(pbkdf2(password, Base64.getDecoder().decode(c.salt()), c.iterations()),
                        Base64.getDecoder().decode(c.hash())))
                .orElse(false);
    }

    /**
     * Signs in with name and password: a session token, or 401 (always the same message). A local
     * account is checked first; without one, the company directory, when there is one.
     */
    public Access.IssuedToken signIn(String name, String password) {
        Optional<Access.IssuedToken> token = signInLocally(name, password);
        if (token.isPresent()) {
            return token.get();
        }
        if (directory != null) {
            return signInWithDirectory(name, password);
        }
        throw refused(name, "unknown or no local account");
    }

    /** Empty when there is no local account of that name (or local accounts are off). */
    private synchronized Optional<Access.IssuedToken> signInLocally(String name, String password) {
        Instant now = clock.instant();
        Optional<Model.Identity> identity = store.identities().stream()
                .filter(i -> i.name().equalsIgnoreCase(name == null ? "" : name) && i.kind().equals("person") && !i.disabled()).findFirst();
        Optional<Model.Credential> credential = local
                ? identity.flatMap(i -> store.credentials().stream().filter(c -> c.identity().equals(i.id())).findFirst())
                : Optional.empty();
        if (credential.isEmpty()) {
            if (directory == null) {
                pbkdf2(password == null ? "" : password, DUMMY_SALT, ITERATIONS);
            }
            return Optional.empty();
        }
        Model.Credential c = credential.get();
        if (c.lockedUntil() != null && Instant.parse(c.lockedUntil()).isAfter(now)) {
            pbkdf2(password == null ? "" : password, DUMMY_SALT, ITERATIONS);
            throw refused(name, "locked");
        }
        boolean ok = MessageDigest.isEqual(pbkdf2(password == null ? "" : password, Base64.getDecoder().decode(c.salt()), c.iterations()),
                Base64.getDecoder().decode(c.hash()));
        if (!ok) {
            int failures = c.failures() + 1;
            String locked = failures >= MAX_FAILURES ? now.plus(LOCK).toString() : null;
            store.saveCredential(new Model.Credential(c.identity(), c.salt(), c.iterations(), c.hash(), locked == null ? failures : 0, locked, c.mustChange()));
            throw refused(name, locked == null ? "wrong password" : "wrong password; locked for 15 minutes");
        }
        store.saveCredential(new Model.Credential(c.identity(), c.salt(), c.iterations(), c.hash(), 0, null, c.mustChange()));
        Access.IssuedToken token = access.issue(identity.get(), identity.get().scopes(), "session", now.plus(SESSION));
        store.append(new Model.Activity(now.toString(), identity.get().name(), "person", "signin", identity.get().name(), "ok",
                Map.of("token", token.record().id())));
        return Optional.of(token);
    }

    private Access.IssuedToken signInWithDirectory(String name, String password) {
        String key = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        long now = clock.millis();
        long[] failures = directoryFailures.getOrDefault(key, new long[] {0, 0});
        if (failures[1] > now) {
            throw refused(name, "locked");
        }
        Optional<SignIns.Person> person;
        try {
            person = directory.authenticate(name, password);
        } catch (IllegalStateException e) {
            store.append(new Model.Activity(clock.instant().toString(), name == null ? "" : name, "person", "signin", name == null ? "" : name,
                    "error", Map.of("reason", "directory unavailable")));
            throw new ApiException(503, "UNAVAILABLE", "the company directory can't be reached; try again in a moment");
        }
        if (person.isEmpty()) {
            long count = failures[0] + 1;
            boolean lock = count >= MAX_FAILURES;
            directoryFailures.put(key, lock ? new long[] {0, now + LOCK.toMillis()} : new long[] {count, 0});
            throw refused(name, lock ? "wrong password; locked for 15 minutes" : "wrong password (directory)");
        }
        directoryFailures.remove(key);
        try {
            return signIns.signIn(person.get(), directoryRoles);
        } catch (SignIns.Refused e) {
            // the same answer as a wrong password; the reason is in the activity log
            throw new ApiException(401, "UNAUTHORIZED", "the name or password is wrong, or the account is locked for a while");
        }
    }

    public SignIns signIns() {
        return signIns;
    }

    private ApiException refused(String name, String reason) {
        store.append(new Model.Activity(clock.instant().toString(), name == null ? "" : name, "person", "signin", name == null ? "" : name,
                "denied", Map.of("reason", reason)));
        return new ApiException(401, "UNAUTHORIZED", "the name or password is wrong, or the account is locked for a while");
    }

    static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
