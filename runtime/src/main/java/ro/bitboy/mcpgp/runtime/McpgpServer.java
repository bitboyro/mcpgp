package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.Banner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import ro.bitboy.mcpgp.runtime.config.ConfigException;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.manifest.ManifestException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.runtime.audit.AuditLog;
import ro.bitboy.mcpgp.runtime.mcp.Ids;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The server process: {@code java -jar <server>.jar [--config <file>] [--<key> <value>…]}.
 * Spring Boot hosts the MCP server; the config is mcpgp's own (runtime-config.md), not Spring's.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class McpgpServer {

    /** How the process talks to its client in stdio mode; replaced in tests. */
    public record Stdio(InputStream in, OutputStream out, Runnable onClose) {
    }

    public static void main(String[] args) {
        if (args.length > 0 && ServerCommands.COMMANDS.contains(args[0])) {
            System.exit(new ServerCommands(System.getenv(), Path.of("").toAbsolutePath(), System.in, System.out).run(args));
            return;
        }
        Startup startup;
        try {
            startup = Startup.load(args, System.getenv(), Path.of("").toAbsolutePath());
        } catch (ConfigException | ManifestException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        ConfigurableApplicationContext[] context = new ConfigurableApplicationContext[1];
        Stdio stdio = new Stdio(System.in, System.out, () -> {
            if (context[0] != null) {
                context[0].close();
            }
            System.out.flush();
            System.exit(0);
        });
        context[0] = start(startup, stdio);
    }

    /** Starts the server; in stdio mode it serves {@code stdio} until the client closes it. */
    public static ConfigurableApplicationContext start(Startup startup, Stdio stdio) {
        RuntimeConfig config = startup.config();
        boolean http = config.server().transport().equals("http");
        SpringApplication app = new SpringApplication(McpgpServer.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setWebApplicationType(http ? WebApplicationType.SERVLET : WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "spring.main.log-startup-info", "false",
                "logging.level.root", config.logLevel().toUpperCase(),
                "server.address", config.server().host(),
                "server.port", String.valueOf(config.server().port()),
                "mcpgp.transport", config.server().transport()));
        app.addInitializers(ctx -> {
            ctx.getBeanFactory().registerSingleton("startup", startup);
            ctx.getBeanFactory().registerSingleton("stdio", stdio);
        });
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpgpServer.class);
        config.warnings().forEach(log::warn);

        return app.run();
    }

    @Bean
    Manifest manifest(Startup startup) {
        return startup.manifest();
    }

    @Bean
    RuntimeConfig runtimeConfig(Startup startup) {
        return startup.config();
    }

    /** Telemetry, off by default (audit-and-errors §4); flushed when the server stops. */
    @Bean(destroyMethod = "close")
    ro.bitboy.mcpgp.runtime.telemetry.Telemetry telemetry(Manifest manifest, RuntimeConfig config) {
        return ro.bitboy.mcpgp.runtime.telemetry.Telemetry.start(config.telemetry(), manifest.serverName(), manifest.serverVersion(),
                System.getenv());
    }

    @Bean
    McpServerFactory mcpServerFactory(Manifest manifest, RuntimeConfig config, ro.bitboy.mcpgp.runtime.telemetry.Telemetry telemetry) {
        return new McpServerFactory(manifest, config);
    }

    /** Config reload (runtime-config §4.6), for either transport. */
    @Bean(initMethod = "start", destroyMethod = "close")
    Reloader reloader(Startup startup, McpServerFactory factory, McpSyncServer server) {
        return new Reloader(startup, factory, server);
    }

    /** server.start / server.stop and, for stdio, session.start / session.end (audit-and-errors §2). */
    @Bean(initMethod = "start", destroyMethod = "stop")
    Lifecycle auditLifecycle(McpServerFactory factory, RuntimeConfig config) {
        return new Lifecycle(factory.audit(), config);
    }

    static final class Lifecycle {
        private final AuditLog audit;
        private final RuntimeConfig config;
        private final boolean stdio;

        Lifecycle(AuditLog audit, RuntimeConfig config) {
            this.audit = audit;
            this.config = config;
            this.stdio = config.server().transport().equals("stdio");
        }

        void start() {
            ObjectNode event = audit.event("server.start", "ok", Ids.correlation());
            event.putObject("actor").put("kind", "system");
            event.put("inlineSecrets", config.inlineSecrets());
            event.put("configHash", config.configHash());
            audit.write(event);
            if (stdio) {
                audit.write(system(audit.event("session.start", "ok", Ids.correlation())));
            }
        }

        void stop() {
            if (stdio) {
                audit.write(system(audit.event("session.end", "ok", Ids.correlation())));
            }
            audit.write(system(audit.event("server.stop", "ok", Ids.correlation())));
        }

        private static ObjectNode system(ObjectNode event) {
            event.putObject("actor").put("kind", "system");
            return event;
        }
    }

    @Bean(destroyMethod = "closeGracefully")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "mcpgp.transport", havingValue = "stdio")
    McpSyncServer stdioServer(McpServerFactory factory, Manifest manifest, RuntimeConfig config, Stdio stdio) {
        // on stdin EOF, requests already read get as long as the slowest instance's timeout to finish
        int longest = config.allInstances().stream().mapToInt(RuntimeConfig.Instance::timeoutSeconds).max().orElse(30);
        StdioTransport transport = new StdioTransport(new JacksonMcpJsonMapper(new ObjectMapper()), stdio.in(), stdio.out(),
                manifest.mcpRevisions(), Duration.ofSeconds(longest + 10L), stdio.onClose(), config.server().pageSize());
        McpSyncServer server = factory.create(transport);
        // toolsets: the one session's enabled toolsets become the server's tool list
        factory.toolsets().ifPresent(toolsets -> toolsets.onListChanged(session -> factory.showToolsets(server, session)));
        return server;
    }
}
