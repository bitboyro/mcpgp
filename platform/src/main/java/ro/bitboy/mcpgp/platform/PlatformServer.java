package ro.bitboy.mcpgp.platform;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import ro.bitboy.mcpgp.platform.api.AuthFilter;
import ro.bitboy.mcpgp.platform.api.WebUi;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Accounts;
import ro.bitboy.mcpgp.platform.service.Guardrails;
import ro.bitboy.mcpgp.platform.service.Transfer;
import ro.bitboy.mcpgp.platform.service.Builds;
import ro.bitboy.mcpgp.platform.service.Compilation;
import ro.bitboy.mcpgp.platform.service.Edits;
import ro.bitboy.mcpgp.platform.service.Library;
import ro.bitboy.mcpgp.platform.service.Servers;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.PostgresStore;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * The platform: {@code java -jar mcpgp-platform.jar [--config platform.yaml]};
 * {@code setup} to write the Docker Compose files, config and secrets for this image into a folder;
 * {@code check} to see what would go wrong with a config before starting;
 * {@code init --admin <name>} once to create the first administrator (or {@code admin} in the config);
 * {@code copy-store --from <directory>} to move a file store's data into the configured store.
 */
// Spring Security only does OIDC sign-in (SignInSecurity), from platform.yaml: no generated password, no spring.security.* clients
@SpringBootApplication(proxyBeanMethods = false, exclude = {
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class})
public class PlatformServer {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("setup")) {
            java.io.Console console = System.console();
            java.io.BufferedReader in = console == null ? null : new java.io.BufferedReader(console.reader());
            System.exit(new Setup(System.getenv(), in, System.out).run(args));
            return;
        }
        if (args.length > 0 && args[0].equals("check")) {
            PlatformConfig checked;
            try {
                checked = PlatformConfig.load(PlatformConfig.configFile(args), System.getenv());
            } catch (RuntimeException e) {
                System.out.println("✗ config: " + e.getMessage());
                System.exit(1);
                return;
            }
            System.exit(new Check(checked, System.getenv(), System.out).run());
            return;
        }
        PlatformConfig config = PlatformConfig.load(PlatformConfig.configFile(args), System.getenv());
        if (args.length > 0 && args[0].equals("init")) {
            System.exit(init(config, args, System.out));
            return;
        }
        if (args.length > 0 && args[0].equals("copy-store")) {
            System.exit(copyStore(config, args, System.out));
            return;
        }
        start(config);
    }

    /** HTTPS from {@code http.tls}: an SSL bundle that is reloaded when its files change; nothing without it. */
    static Map<String, Object> tlsProperties(PlatformConfig config) {
        PlatformConfig.Tls tls = config.tls();
        if (tls == null) {
            return Map.of();
        }
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("server.ssl.bundle", "mcpgp");
        if (tls.pem()) {
            p.put("spring.ssl.bundle.pem.mcpgp.keystore.certificate", "file:" + tls.certificate());
            p.put("spring.ssl.bundle.pem.mcpgp.keystore.private-key", "file:" + tls.privateKey());
            p.put("spring.ssl.bundle.pem.mcpgp.reload-on-update", "true");
        } else {
            String password = Secrets.resolve(tls.password(), System.getenv())
                    .orElseThrow(() -> new IllegalStateException("http.tls.password (" + tls.password() + ") can't be read"));
            p.put("spring.ssl.bundle.jks.mcpgp.keystore.location", "file:" + tls.keystore());
            p.put("spring.ssl.bundle.jks.mcpgp.keystore.password", password);
            p.put("spring.ssl.bundle.jks.mcpgp.keystore.type", "PKCS12");
            p.put("spring.ssl.bundle.jks.mcpgp.reload-on-update", "true");
        }
        return p;
    }

    /** The configured store: the file store, or PostgreSQL. */
    public static Store openStore(PlatformConfig config) {
        PlatformConfig.Postgres pg = config.postgres();
        if (pg == null) {
            return new FileStore(config.storageRoot());
        }
        String password = pg.password() == null ? null : Secrets.resolve(pg.password(), System.getenv())
                .orElseThrow(() -> new IllegalStateException("storage.password (" + pg.password() + ") can't be read"));
        return new PostgresStore(pg.url(), pg.user(), password, pg.schema(), pg.poolSize());
    }

    private static void close(Store store) {
        if (store instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                // closing anyway
            }
        }
    }

    /**
     * Copies everything from a file store into the configured (empty) store: the export with build
     * artifacts, then build logs and the activity log, which an export leaves out.
     */
    static int copyStore(PlatformConfig config, String[] args, PrintStream out) {
        String from = null;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--from")) {
                from = args[i + 1];
            }
        }
        if (from == null) {
            out.println("Usage: copy-store --from <file store directory> [--config platform.yaml]");
            return 2;
        }
        java.nio.file.Path source = java.nio.file.Path.of(from).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(source.resolve("store.json"))) {
            out.println("✗ " + source + " is not a file store (no store.json)");
            return 1;
        }
        if (config.postgres() == null && source.equals(config.storageRoot().toAbsolutePath().normalize())) {
            out.println("✗ the source is the configured store");
            return 1;
        }
        Store files = new FileStore(source);
        Store target = openStore(config);
        try {
            if (!target.servers().isEmpty() || !target.library().isEmpty() || !target.identities().isEmpty()) {
                out.println("✗ " + target.describe() + " is not empty; copy into an empty store");
                return 1;
            }
            Map<String, Integer> counts = new Transfer(target).importAll(new Transfer(files).export(true));
            int logs = 0;
            for (Model.Build build : files.builds()) {
                var log = files.buildFile(build.id(), "log.txt");
                if (log.isPresent()) {
                    target.appendBuildLog(build.id(), new String(log.get(), java.nio.charset.StandardCharsets.UTF_8));
                    logs++;
                }
            }
            java.util.List<Model.Activity> activity = new java.util.ArrayList<>(files.activity(a -> true, Integer.MAX_VALUE));
            java.util.Collections.reverse(activity);
            activity.forEach(target::append);
            target.append(new Model.Activity(Instant.now().toString(), "platform", "system", "platform.copy-store", source.toString(), "ok",
                    Map.of("to", target.describe())));
            out.println("✓ copied " + source + " to " + target.describe() + ": " + counts.get("servers") + " servers, "
                    + counts.get("library") + " library items, " + counts.get("versions") + " versions, " + counts.get("builds")
                    + " builds (" + logs + " logs), " + counts.get("identities") + " identities, " + activity.size() + " activity entries");
            return 0;
        } finally {
            close(target);
        }
    }

    public static ConfigurableApplicationContext start(PlatformConfig config) {
        SpringApplication app = new SpringApplication(PlatformServer.class);
        app.setBannerMode(Banner.Mode.OFF);
        Map<String, Object> properties = new java.util.LinkedHashMap<>(Map.of(
                "server.address", config.host(),
                "server.port", String.valueOf(config.port()),
                "spring.main.log-startup-info", "false",
                // uploads up to the project limit (spec/platform-api.md §8)
                "spring.servlet.multipart.enabled", "false",
                "server.tomcat.max-swallow-size", "-1",
                "logging.level.com.zaxxer.hikari", "warn"));
        properties.putAll(tlsProperties(config));
        app.setDefaultProperties(properties);
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("platformConfig", config));
        return app.run();
    }

    /** Creates the first administrator and prints a one-time admin token; refuses when identities exist. */
    static int init(PlatformConfig config, String[] args, PrintStream out) {
        String admin = null;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals("--admin")) {
                admin = args[i + 1];
            }
        }
        if (admin == null) {
            out.println("Usage: init --admin <name> [--config platform.yaml]");
            return 2;
        }
        Store store = openStore(config);
        try {
            return init(store, admin, out);
        } finally {
            close(store);
        }
    }

    private static int init(Store store, String admin, PrintStream out) {
        if (!store.identities().isEmpty()) {
            out.println("✗ the store " + store.describe() + " already has identities; use an admin token to add more");
            return 1;
        }
        Access access = new Access(store);
        Model.Identity identity = access.createIdentity("person", admin, Set.copyOf(Access.SCOPES), null);
        String password = System.getenv("MCPGP_PLATFORM_ADMIN_PASSWORD");
        if (password != null && !password.isBlank()) {
            new Accounts(store, access, java.time.Clock.systemUTC()).setPassword(identity, password);
            out.println("✓ local account " + admin + " can sign in with the password from MCPGP_PLATFORM_ADMIN_PASSWORD");
        }
        Access.IssuedToken token = access.issue(identity, Set.of("admin"), "bootstrap", Instant.now().plus(30, ChronoUnit.DAYS));
        store.append(new Model.Activity(Instant.now().toString(), admin, "person", "platform.init", store.describe(), "ok",
                Map.of("token", token.record().id())));
        out.println("✓ created administrator " + admin + " in " + store.describe());
        out.println("  Admin token (shown once, valid 30 days; create personal tokens with it, then revoke it):");
        out.println("  " + token.value());
        return 0;
    }

    /** The store; on first start, the administrator from {@code admin} in the config. Closed on shutdown (inferred close()). */
    @Bean
    Store store(PlatformConfig config) {
        Store store = openStore(config);
        Deployment.bootstrap(config, store);
        Deployment.warnings(config, store).forEach(w -> org.slf4j.LoggerFactory.getLogger(PlatformServer.class).warn("deployment: {}", w));
        return store;
    }

    @Bean
    Access access(Store store) {
        return new Access(store);
    }

    @Bean
    Servers servers(Store store) {
        return new Servers(store);
    }

    @Bean
    Compilation compilation(Store store, PlatformConfig config) {
        return new Compilation(store, config.workspace());
    }

    @Bean(destroyMethod = "close")
    ro.bitboy.mcpgp.platform.service.Trials trials(Compilation compilation) {
        return new ro.bitboy.mcpgp.platform.service.Trials(compilation);
    }

    @Bean
    Edits edits(Store store, Servers servers, Compilation compilation) {
        return new Edits(store, servers, compilation);
    }

    @Bean
    ro.bitboy.mcpgp.platform.service.FromSpec fromSpec(Store store, Servers servers, Edits edits) {
        return new ro.bitboy.mcpgp.platform.service.FromSpec(store, servers, edits);
    }

    @Bean
    Library library(Store store) {
        return new Library(store);
    }

    @Bean
    ro.bitboy.mcpgp.platform.service.SharedNotices sharedNotices(Store store, Library library, Edits edits, PlatformConfig config) {
        return new ro.bitboy.mcpgp.platform.service.SharedNotices(store, library, edits, config.workspace());
    }

    @Bean
    Accounts accounts(Store store, Access access, PlatformConfig config) {
        ro.bitboy.mcpgp.platform.service.Directory directory = config.ldap() == null ? null
                : new ro.bitboy.mcpgp.platform.service.Directory(config.ldap(), System.getenv());
        return new Accounts(store, access, java.time.Clock.systemUTC(), config.localAccounts(), directory,
                config.ldap() == null ? Map.of() : config.ldap().roles());
    }

    @Bean
    Guardrails guardrails(Store store, Builds builds, PlatformConfig config) {
        return new Guardrails(store, builds, config.guardrails(), java.time.Clock.systemUTC());
    }

    @Bean
    Transfer transfer(Store store) {
        return new Transfer(store);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    Builds builds(Store store, Compilation compilation, PlatformConfig config) {
        return new Builds(store, compilation, config, System.getenv(), java.time.Clock.systemUTC());
    }

    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(Access access) {
        FilterRegistrationBean<AuthFilter> registration = new FilterRegistrationBean<>(new AuthFilter(access));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    FilterRegistrationBean<WebUi.Headers> webHeaders() {
        FilterRegistrationBean<WebUi.Headers> registration = new FilterRegistrationBean<>(new WebUi.Headers());
        registration.addUrlPatterns("/*");
        registration.setOrder(0);
        return registration;
    }
}
