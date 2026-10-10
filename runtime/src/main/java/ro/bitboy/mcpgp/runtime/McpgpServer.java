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
        config.warnings().forEach(w -> org.slf4j.LoggerFactory.getLogger(McpgpServer.class).warn(w));
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

    @Bean
    McpServerFactory mcpServerFactory(Manifest manifest, RuntimeConfig config) {
        return new McpServerFactory(manifest, config);
    }

    @Bean(destroyMethod = "closeGracefully")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "mcpgp.transport", havingValue = "stdio")
    McpSyncServer stdioServer(McpServerFactory factory, Manifest manifest, RuntimeConfig config, Stdio stdio) {
        // on stdin EOF, requests already read get as long as the slowest instance's timeout to finish
        int longest = config.allInstances().stream().mapToInt(RuntimeConfig.Instance::timeoutSeconds).max().orElse(30);
        StdioTransport transport = new StdioTransport(new JacksonMcpJsonMapper(new ObjectMapper()), stdio.in(), stdio.out(),
                manifest.mcpRevisions(), Duration.ofSeconds(longest + 10L), stdio.onClose());
        return factory.create(transport);
    }
}
