package ro.bitboy.mcpgp.platform;

import ro.bitboy.mcpgp.packager.Minisign;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * {@code check} (docs/guides/self-hosting.md): what would go wrong when the platform starts with this
 * config, without starting it: secrets that can't be read, a store it can't reach or write, a signing
 * key that isn't one, an identity provider that doesn't answer, and the start-up warnings. Changes
 * nothing; exit code 1 when something would fail.
 *
 * <pre>
 * docker compose run --rm platform check
 * </pre>
 */
final class Check {

    private final PlatformConfig config;
    private final Map<String, String> env;
    private final PrintStream out;
    private int failures;

    Check(PlatformConfig config, Map<String, String> env, PrintStream out) {
        this.config = config;
        this.env = env;
        this.out = out;
    }

    int run() {
        out.println("mcpgp platform check");
        ok("config: workspace " + config.workspace() + ", listening on " + config.host() + ":" + config.port()
                + (config.publicUrl() == null ? "" : ", public address " + config.publicUrl()));

        Map<String, String> secrets = new LinkedHashMap<>();
        if (config.admin() != null) {
            secrets.put("admin.password", config.admin().password());
        }
        if (config.postgres() != null && config.postgres().password() != null) {
            secrets.put("storage.password", config.postgres().password());
        }
        if (config.signingKey() != null) {
            secrets.put("signing.key", config.signingKey());
        }
        if (config.oidc() != null && config.oidc().clientSecret() != null) {
            secrets.put("auth.oidc.clientSecret", config.oidc().clientSecret());
        }
        if (config.ldap() != null && config.ldap().bindPassword() != null) {
            secrets.put("auth.ldap.bindPassword", config.ldap().bindPassword());
        }
        Map<String, String> values = new LinkedHashMap<>();
        secrets.forEach((name, ref) -> {
            var value = Secrets.resolve(ref, env);
            if (value.isEmpty()) {
                fail(name + ": " + ref + " can't be read (missing, empty, or not readable for this user)");
            } else {
                values.put(name, value.get());
                ok(name + ": readable");
            }
        });
        if (values.containsKey("admin.password") && values.get("admin.password").length() < 12) {
            fail("admin.password: at least 12 characters");
        }
        if (values.containsKey("signing.key")) {
            try {
                Minisign.Keys keys = Minisign.readSecretKey(values.get("signing.key"));
                ok("signing.key: a signing key (key id " + Minisign.keyIdText(keys.keyId()) + ")");
            } catch (RuntimeException e) {
                fail("signing.key: not a signing key from mcpgp keygen (" + e.getMessage() + ")");
            }
        }

        store(values.get("storage.password"));

        if (config.oidc() != null) {
            String discovery = config.oidc().issuer().replaceAll("/+$", "") + "/.well-known/openid-configuration";
            try {
                HttpResponse<Void> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                        .send(HttpRequest.newBuilder(URI.create(discovery)).timeout(Duration.ofSeconds(10)).GET().build(),
                                HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    ok("company sign-in: " + config.oidc().issuer() + " answers");
                } else {
                    fail("company sign-in: " + discovery + " answered " + response.statusCode());
                }
            } catch (Exception e) {
                fail("company sign-in: " + discovery + " can't be reached (" + e.getMessage() + ")");
            }
        }

        for (String warning : Deployment.warnings(config, null)) {
            out.println("! " + warning);
        }
        out.println(failures == 0 ? "✓ ready to start" : "✗ " + failures + " problem" + (failures == 1 ? "" : "s") + "; fix them before starting");
        return failures == 0 ? 0 : 1;
    }

    /** The store can be reached and written, without opening it (a running platform holds it). */
    private void store(String password) {
        PlatformConfig.Postgres pg = config.postgres();
        if (pg == null) {
            Path root = config.storageRoot().toAbsolutePath().normalize();
            Path existing = root;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !Files.isDirectory(existing) || !Files.isWritable(existing)) {
                fail("store: " + root + " can't be written by this user");
            } else {
                ok("store: files in " + root + (Files.exists(root.resolve("store.json")) ? "" : " (new)"));
            }
            return;
        }
        Properties props = new Properties();
        if (pg.user() != null) {
            props.setProperty("user", pg.user());
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", "10");
        props.setProperty("loginTimeout", "10");
        try (Connection c = DriverManager.getConnection(pg.url(), props); Statement st = c.createStatement()) {
            st.execute("SELECT 1");
            ok("store: PostgreSQL at " + pg.url() + " answers (schema " + pg.schema() + ")");
        } catch (Exception e) {
            fail("store: PostgreSQL at " + pg.url() + " can't be reached: " + e.getMessage());
        }
    }

    private void ok(String line) {
        out.println("✓ " + line);
    }

    private void fail(String line) {
        out.println("✗ " + line);
        failures++;
    }
}
