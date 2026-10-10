package ro.bitboy.mcpgp.platform.service;

import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sign-in with a company account, from OIDC or a directory (spec/platform-api.md §3). The person is
 * matched by their account ({@code external}); at the first sign-in, a person who has no company
 * account yet is linked when their name is the account's verified email (OIDC, where user names may be
 * editable) or its user name (LDAP), which is how the administrator {@code init} created signs in. Otherwise a person is created, but only when one of their groups maps to
 * a role: people in no mapped group get no identity.
 *
 * <p>Identities created this way ({@code owner: directory}) follow their groups at every sign-in and
 * are refused once no group maps any more. Identities made on the platform keep the scopes an
 * administrator gave them.
 */
public final class SignIns {

    public static final String DIRECTORY = "directory";

    /**
     * A person as the company account describes them; {@code subject} is stable (the OIDC sub, the LDAP
     * DN); {@code email} only when the provider verified it.
     */
    public record Person(String source, String subject, String name, String email, Set<String> groups) {
    }

    /** Refused: the account is fine but has no access here (or was disabled). */
    public static final class Refused extends RuntimeException {
        public Refused(String reason) {
            super(reason);
        }
    }

    private final Store store;
    private final Access access;
    private final Clock clock;

    public SignIns(Store store, Access access, Clock clock) {
        this.store = store;
        this.access = access;
        this.clock = clock;
    }

    /** The scopes the person's groups give, through {@code roles} (group name → role); group names match ignoring case. */
    public static Set<String> scopes(Set<String> groups, Map<String, String> roles) {
        Set<String> scopes = new TreeSet<>();
        Set<String> lower = new LinkedHashSet<>();
        groups.forEach(g -> lower.add(g.toLowerCase(Locale.ROOT)));
        roles.forEach((group, role) -> {
            if (lower.contains(group.toLowerCase(Locale.ROOT))) {
                scopes.addAll(Access.ROLES.get(role));
            }
        });
        return scopes;
    }

    public synchronized Access.IssuedToken signIn(Person person, Map<String, String> roles) {
        Instant now = clock.instant();
        String external = person.source() + ":" + person.subject();
        Set<String> scopes = scopes(person.groups(), roles);
        Optional<Model.Identity> found = store.identities().stream().filter(i -> external.equals(i.external())).findFirst();
        if (found.isEmpty()) {
            found = store.identities().stream()
                    .filter(i -> i.kind().equals("person") && i.external() == null
                            && (person.source().equals("ldap") && i.name().equalsIgnoreCase(person.name())
                            || person.email() != null && i.name().equalsIgnoreCase(person.email())))
                    .findFirst()
                    .map(i -> save(i, i.scopes(), external));
            found.ifPresent(i -> record(now, i.name(), "identity.link", Map.of("via", person.source())));
        }
        Model.Identity identity;
        if (found.isPresent()) {
            identity = found.get();
            if (identity.disabled()) {
                throw refused(now, person, "disabled");
            }
            if (DIRECTORY.equals(identity.owner())) {
                if (scopes.isEmpty()) {
                    throw refused(now, person, "no mapped group");
                }
                if (!scopes.equals(new TreeSet<>(identity.scopes()))) {
                    identity = save(identity, scopes, identity.external());
                    record(now, identity.name(), "identity.scopes", Map.of("via", person.source(), "scopes", scopes));
                }
            }
        } else {
            if (scopes.isEmpty()) {
                throw refused(now, person, "no mapped group");
            }
            String name = uniqueName(person);
            Model.Identity created = access.createIdentity("person", name, scopes, DIRECTORY);
            identity = save(created, created.scopes(), external);
            record(now, identity.name(), "identity.create", Map.of("via", person.source(), "scopes", scopes));
        }
        Access.IssuedToken token = access.issue(identity, identity.scopes(), "session", now.plus(Accounts.SESSION));
        store.append(new Model.Activity(now.toString(), identity.name(), "person", "signin", identity.name(), "ok",
                Map.of("token", token.record().id(), "via", person.source())));
        return token;
    }

    private Model.Identity save(Model.Identity i, Set<String> scopes, String external) {
        Model.Identity updated = new Model.Identity(i.id(), i.kind(), i.name(), new LinkedHashSet<>(scopes), i.owner(), i.created(), i.disabled(), external);
        store.saveIdentity(updated);
        return updated;
    }

    /** Identity names allow letters, digits and . _ @ -; a name another identity has gets a number. */
    private String uniqueName(Person person) {
        String base = (person.name() == null || person.name().isBlank() ? person.email() : person.name());
        base = (base == null ? "person" : base).replaceAll("[^A-Za-z0-9._@-]+", "-").replaceAll("^[^A-Za-z0-9]+", "");
        if (base.isEmpty()) {
            base = "person";
        }
        base = base.substring(0, Math.min(72, base.length()));
        String name = base;
        for (int n = 2; taken(name); n++) {
            name = base + "-" + n;
        }
        return name;
    }

    private boolean taken(String name) {
        return store.identities().stream().anyMatch(i -> i.name().equalsIgnoreCase(name));
    }

    private Refused refused(Instant now, Person person, String reason) {
        store.append(new Model.Activity(now.toString(), person.name() == null ? "" : person.name(), "person", "signin",
                person.name() == null ? "" : person.name(), "denied", Map.of("reason", reason, "via", person.source())));
        return new Refused(reason);
    }

    private void record(Instant now, String name, String action, Map<String, Object> details) {
        store.append(new Model.Activity(now.toString(), name, "person", action, name, "ok", details));
    }
}
