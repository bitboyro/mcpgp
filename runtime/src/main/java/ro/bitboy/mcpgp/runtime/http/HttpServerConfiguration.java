package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * The HTTP transport: Streamable HTTP at {@code server.basePath}, guarded by {@link InboundFilter};
 * health endpoints on the management port when one is set, else on the main port.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mcpgp.transport", havingValue = "http")
public class HttpServerConfiguration {

    @Bean
    HttpServletStreamableServerTransportProvider mcpServlet(RuntimeConfig config) {
        return HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
                .mcpEndpoint(config.server().basePath())
                .keepAliveInterval(Duration.ofSeconds(30))
                .contextExtractor(request -> {
                    Map<String, Object> context = new HashMap<>();
                    Object client = request.getAttribute(InboundFilter.CLIENT);
                    if (client != null) {
                        context.put(McpServerFactory.CLIENT_KEY, client);
                    }
                    return McpTransportContext.create(context);
                })
                .build();
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer httpServer(McpServerFactory factory, Manifest manifest, HttpServletStreamableServerTransportProvider servlet) {
        return factory.create(new RevisionLimitedTransport(servlet, manifest.mcpRevisions()));
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpEndpoint(
            HttpServletStreamableServerTransportProvider servlet, RuntimeConfig config) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(servlet, config.server().basePath());
        registration.setAsyncSupported(true);
        registration.setName("mcp");
        return registration;
    }

    @Bean
    FilterRegistrationBean<InboundFilter> inboundFilter(RuntimeConfig config, McpServerFactory factory) {
        FilterRegistrationBean<InboundFilter> registration =
                new FilterRegistrationBean<>(new InboundFilter(config.server(), factory.audit()));
        registration.addUrlPatterns(config.server().basePath());
        registration.setAsyncSupported(true);
        registration.setOrder(1);
        return registration;
    }

    @Bean
    ServletRegistrationBean<HealthServlet> health(ro.bitboy.mcpgp.runtime.Reloader reloader) {
        ServletRegistrationBean<HealthServlet> registration = new ServletRegistrationBean<>(
                new HealthServlet(() -> reloader.current().config().allInstances(), Clock.systemUTC()), "/health/*");
        registration.setName("health");
        return registration;
    }

    /** With a management port, health answers only there and the MCP endpoint only on the main port. */
    @Bean
    FilterRegistrationBean<HttpFilter> portSeparation(RuntimeConfig config) {
        Integer management = config.server().managementPort();
        HttpFilter filter = new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                if (management != null) {
                    boolean onManagement = request.getLocalPort() == management;
                    boolean healthPath = request.getRequestURI().startsWith("/health/");
                    if (onManagement != healthPath) {
                        response.setStatus(404);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<HttpFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setAsyncSupported(true);
        registration.setOrder(0);
        return registration;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> managementConnector(RuntimeConfig config) {
        return factory -> {
            Integer port = config.server().managementPort();
            if (port != null) {
                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(port);
                connector.setProperty("address", config.server().host());
                factory.addAdditionalConnectors(connector);
            }
        };
    }
}
