package ro.bitboy.mcpgp.platform;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import ro.bitboy.mcpgp.platform.api.AuthFilter;
import ro.bitboy.mcpgp.platform.service.Access;
import ro.bitboy.mcpgp.platform.service.Accounts;
import ro.bitboy.mcpgp.platform.service.Guardrails;
import ro.bitboy.mcpgp.platform.service.Transfer;
import ro.bitboy.mcpgp.platform.service.Builds;
import ro.bitboy.mcpgp.platform.service.Compilation;
import ro.bitboy.mcpgp.platform.service.Library;
import ro.bitboy.mcpgp.platform.service.Servers;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * The platform: {@code java -jar mcpgp-platform.jar [--config platform.yaml]}, and
 * {@code init --admin <name>} once to create the first administrator.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class PlatformServer {

    public static void main(String[] args) {
        PlatformConfig config = PlatformConfig.load(PlatformConfig.configFile(args), System.getenv());
        if (args.length > 0 && args[0].equals("init")) {
            System.exit(init(config, args, System.out));
            return;
        }
        start(config);
    }

    public static ConfigurableApplicationContext start(PlatformConfig config) {
        SpringApplication app = new SpringApplication(PlatformServer.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setDefaultProperties(Map.of(
                "server.address", config.host(),
                "server.port", String.valueOf(config.port()),
                "spring.main.log-startup-info", "false",
                // uploads up to the project limit (spec/platform-api.md §8)
                "spring.servlet.multipart.enabled", "false",
                "server.tomcat.max-swallow-size", "-1"));
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
        Store store = new FileStore(config.storageRoot());
        if (!store.identities().isEmpty()) {
            out.println("✗ the store at " + config.storageRoot() + " already has identities; use an admin token to add more");
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
        store.append(new Model.Activity(Instant.now().toString(), admin, "person", "platform.init", config.storageRoot().toString(), "ok",
                Map.of("token", token.record().id())));
        out.println("✓ created administrator " + admin + " in " + config.storageRoot());
        out.println("  Admin token (shown once, valid 30 days; create personal tokens with it, then revoke it):");
        out.println("  " + token.value());
        return 0;
    }

    @Bean
    Store store(PlatformConfig config) {
        return new FileStore(config.storageRoot());
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

    @Bean
    Library library(Store store) {
        return new Library(store);
    }

    @Bean
    Accounts accounts(Store store, Access access) {
        return new Accounts(store, access, java.time.Clock.systemUTC());
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
}
