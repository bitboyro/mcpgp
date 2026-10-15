package ro.bitboy.mcpgp.runtime.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
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
import java.net.URI;
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
    HttpServletStreamableServerTransportProvider mcpServlet(RuntimeConfig config,
                                                           org.springframework.beans.factory.ObjectProvider<ro.bitboy.mcpgp.runtime.Reloader> reloader) {
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
                    // only the headers instances are configured to read, and only for this request (auth.md §2.4)
                    Map<String, String> credentials = new HashMap<>();
                    for (String header : credentialHeaders(reloader.getObject().current().config())) {
                        String value = request.getHeader(header);
                        if (value != null) {
                            credentials.put(header, value);
                        }
                    }
                    if (!credentials.isEmpty()) {
                        context.put(McpServerFactory.CREDENTIALS_KEY, Map.copyOf(credentials));
                    }
                    // the user's OAuth access token, which InboundFilter checked, for token exchange (auth.md §2.5)
                    String authorization = request.getHeader("Authorization");
                    if (client != null && config.server().authMode().equals("oauth") && authorization != null
                            && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                        context.put(McpServerFactory.SUBJECT_TOKEN_KEY, authorization.substring(7).strip());
                    }
                    return McpTransportContext.create(context);
                })
                .build();
    }

    /** The headers the instances read their credential from, lower case; they follow config reloads. */
    static java.util.Set<String> credentialHeaders(RuntimeConfig config) {
        java.util.Set<String> headers = new java.util.HashSet<>();
        config.allInstances().forEach(i -> i.authEntries().stream().filter(e -> e.path("type").asText().equals("fromRequest"))
                .forEach(e -> headers.add(e.path("header").asText().toLowerCase(java.util.Locale.ROOT))));
        return headers;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer httpServer(McpServerFactory factory, Manifest manifest, HttpServletStreamableServerTransportProvider servlet) {
        McpSyncServer server = factory.create(new RevisionLimitedTransport(servlet, manifest.mcpRevisions()));
        // toolsets: each session has its own tool list (InboundFilter answers tools/list); tell only that session
        // (a client without an open GET stream can't receive it; it sees the change at its next tools/list)
        factory.toolsets().ifPresent(toolsets -> toolsets.onListChanged(session -> servlet
                .notifyClient(session, io.modelcontextprotocol.spec.McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null)
                .subscribe(done -> { }, e -> org.slf4j.LoggerFactory.getLogger(HttpServerConfiguration.class)
                        .debug("No tools/list_changed for session {}: {}", session, e.getMessage()))));
        return server;
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

    /** Requests larger than the input limits allow (safety.inputLimits) are refused before anything reads them whole. */
    @Bean
    FilterRegistrationBean<RequestSizeFilter> requestSizeFilter(RuntimeConfig config, McpServerFactory factory) {
        FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(new RequestSizeFilter(factory.inputLimits().maxRequestBytes()));
        registration.addUrlPatterns(config.server().basePath());
        registration.setAsyncSupported(true);
        registration.setOrder(0);
        return registration;
    }

    @Bean
    FilterRegistrationBean<InboundFilter> inboundFilter(RuntimeConfig config, McpServerFactory factory, ro.bitboy.mcpgp.runtime.Reloader reloader) {
        RuntimeConfig.Server server = config.server();
        OAuthTokens tokens = server.authMode().equals("oauth")
                ? new OAuthTokens(server.auth().path("issuer").asText(), server.auth().path("jwksUri").asText(null), InboundFilter.resource(server),
                        server.auth().path("introspection"), java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                        Clock.systemUTC())
                : null;
        FilterRegistrationBean<InboundFilter> registration =
                new FilterRegistrationBean<>(new InboundFilter(server, factory.audit(), tokens, () -> reloader.current().catalog(),
                        factory.toolsets().orElse(null)));
        registration.addUrlPatterns(config.server().basePath());
        registration.setAsyncSupported(true);
        registration.setOrder(1);
        return registration;
    }

    /**
     * {@code telemetry.exporter: prometheus}: the metrics for Prometheus to scrape, beside health (on the
     * management port when one is set); {@code 404} with any other telemetry setting.
     */
    @Bean
    ServletRegistrationBean<HttpServlet> metrics() {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                var scrape = ro.bitboy.mcpgp.runtime.telemetry.Telemetry.current().scrape();
                if (scrape.isEmpty()) {
                    response.setStatus(404);
                    return;
                }
                response.setContentType(ro.bitboy.mcpgp.runtime.telemetry.Telemetry.scrapeContentType());
                response.getOutputStream().write(scrape.get());
            }
        };
        ServletRegistrationBean<HttpServlet> registration = new ServletRegistrationBean<>(servlet, "/metrics");
        registration.setName("metrics");
        return registration;
    }

    /** {@code oauth} mode: the protected resource metadata (RFC 9728) that tells clients where to sign in. */
    @Bean
    ServletRegistrationBean<HttpServlet> resourceMetadata(RuntimeConfig config) {
        RuntimeConfig.Server server = config.server();
        boolean oauth = server.authMode().equals("oauth");
        ObjectNode metadata = new ObjectMapper().createObjectNode();
        if (oauth) {
            metadata.put("resource", InboundFilter.resource(server));
            metadata.putArray("authorization_servers").add(server.auth().path("issuer").asText());
            ArrayNode scopes = metadata.putArray("scopes_supported");
            server.auth().path("scopes").fieldNames().forEachRemaining(scopes::add);
            metadata.putArray("bearer_methods_supported").add("header");
        }
        byte[] body = metadata.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                if (!oauth) {
                    response.setStatus(404);
                    return;
                }
                response.setContentType("application/json");
                response.setHeader("Cache-Control", "max-age=300");
                response.getOutputStream().write(body);
            }
        };
        String path = oauth ? URI.create(InboundFilter.resourceMetadata(server)).getRawPath() : "/.well-known/oauth-protected-resource/*";
        ServletRegistrationBean<HttpServlet> registration = new ServletRegistrationBean<>(servlet, path, "/.well-known/oauth-protected-resource");
        registration.setName("oauth-protected-resource");
        return registration;
    }

    @Bean
    ServletRegistrationBean<HealthServlet> health(ro.bitboy.mcpgp.runtime.Reloader reloader) {
        ServletRegistrationBean<HealthServlet> registration = new ServletRegistrationBean<>(
                new HealthServlet(() -> reloader.current().config().allInstances(), Clock.systemUTC()), "/health/*");
        registration.setName("health");
        return registration;
    }

    /** With a management port, health and metrics answer only there and the MCP endpoint only on the main port. */
    @Bean
    FilterRegistrationBean<HttpFilter> portSeparation(RuntimeConfig config) {
        Integer management = config.server().managementPort();
        HttpFilter filter = new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                if (management != null) {
                    boolean onManagement = request.getLocalPort() == management;
                    String uri = request.getRequestURI();
                    boolean operational = uri.startsWith("/health/") || uri.equals("/metrics");
                    if (onManagement != operational) {
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
