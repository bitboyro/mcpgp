package ro.bitboy.mcpgp.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Accounts;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Starting the platform as a deployment (docs/guides/self-hosting.md): the first administrator from
 * the config, and warnings about settings that are fine on a laptop but not on a shared installation.
 */
final class Deployment {

    private static final Logger LOG = LoggerFactory.getLogger(Deployment.class);

    private Deployment() {
    }

    /**
     * Creates the administrator from {@code admin} (a local account with all scopes) when the store has
     * no identities yet; later starts leave the store alone, so changing the password in the config
     * does nothing once the account exists.
     */
    static void bootstrap(PlatformConfig config, Store store) {
        PlatformConfig.Admin admin = config.admin();
        if (admin == null || !store.identities().isEmpty()) {
            return;
        }
        String password = Secrets.resolve(admin.password(), System.getenv())
                .orElseThrow(() -> new IllegalStateException("admin.password (" + admin.password() + ") can't be read"));
        Access access = new Access(store);
        Model.Identity identity = access.createIdentity("person", admin.name(), Set.copyOf(Access.SCOPES), null);
        new Accounts(store, access, java.time.Clock.systemUTC()).setPassword(identity, password);
        store.append(new Model.Activity(Instant.now().toString(), admin.name(), "person", "platform.init", store.describe(), "ok",
                Map.of("from", "config")));
        LOG.info("created administrator {} (a local account) in {}", admin.name(), store.describe());
    }

    /** What to fix before people rely on this installation; empty when nothing stands out. Without a store, the administrator isn't checked. */
    static List<String> warnings(PlatformConfig config, Store store) {
        List<String> warnings = new ArrayList<>();
        boolean local = PlatformConfig.loopback(config.host());
        String publicUrl = config.publicUrl();
        if (!local && publicUrl == null) {
            warnings.add("http.publicUrl is not set: set it to the address people use (https://…), so sign-in "
                    + "redirects and secure cookies are right behind the reverse proxy");
        }
        if (publicUrl != null && publicUrl.startsWith("http://") && !PlatformConfig.loopback(URI.create(publicUrl).getHost())) {
            warnings.add("http.publicUrl is http://: passwords and session cookies travel unencrypted; terminate TLS in front of the platform");
        }
        if (config.signingKey() == null) {
            warnings.add("signing.key is not set: packages are built unsigned");
        }
        if (config.postgres() == null && config.storageRoot().toAbsolutePath().normalize().startsWith("/tmp")) {
            warnings.add("the file store is under /tmp (" + config.storageRoot() + "): it may not survive a restart");
        }
        if (store != null && store.identities().isEmpty()) {
            warnings.add("there is no administrator yet: set admin.name and admin.password, or run `init --admin <name>`");
        }
        if (config.localAccounts() && (config.oidc() != null || config.ldap() != null)) {
            warnings.add("local accounts are enabled next to company sign-in; set auth.local.enabled: false once company sign-in works");
        }
        return warnings;
    }
}
