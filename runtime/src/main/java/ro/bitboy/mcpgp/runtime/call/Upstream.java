package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.config.TlsBuilder;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends requests to instances: one HTTP client per instance with its own TLS context, downstream
 * credentials added here (never from tool input), redirects not followed (credentials must not travel
 * to another host). The only repeats are the ones the specs allow: once after a {@code 401} with a
 * fresh OAuth token (auth.md §2.1), once after a TLS failure when certificate files changed (§5.4).
 */
final class Upstream {

    private static final Logger LOG = LoggerFactory.getLogger(Upstream.class);
    /** Headers the agent may never set; the runtime owns them. */
    private static final Set<String> RESERVED = Set.of("authorization", "content-type", "content-length", "host",
            "cookie", "connection", "transfer-encoding", "expect", "upgrade");

    private record Client(HttpClient http, TlsBuilder.InstanceTls tls) {
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    /** apiKey placement per API, from the manifest ({@code in}, {@code name}). */
    private final Map<String, JsonNode> apiKeys;
    private final OAuthTokens tokens;

    Upstream(Map<String, JsonNode> apiKeys, Redactor redactor) {
        this.apiKeys = apiKeys;
        this.tokens = new OAuthTokens(redactor, Clock.systemUTC());
    }

    /**
     * @param publicOperation the operation declares {@code security: []}: no credentials are sent
     */
    HttpResponse<byte[]> send(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, boolean publicOperation) {
        ro.bitboy.mcpgp.runtime.telemetry.Telemetry.Upstream traced = ro.bitboy.mcpgp.runtime.telemetry.Telemetry.current()
                .upstream(instance.api(), instance.id(), request.method());
        Integer status = null;
        try {
            HttpResponse<byte[]> response = sendUntraced(instance, request, publicOperation);
            status = response.statusCode();
            return response;
        } finally {
            traced.end(status);
        }
    }

    private HttpResponse<byte[]> sendUntraced(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, boolean publicOperation) {
        String key = instance.api() + "/" + instance.id();
        Client client = clients.computeIfAbsent(key, k -> client(instance, instance.tlsContext()));
        HttpResponse<byte[]> response;
        try {
            response = attempt(instance, request, client, publicOperation);
        } catch (SSLException e) {
            if (client.tls() == null || !client.tls().filesChanged()) {
                throw tlsError(instance, e);
            }
            LOG.info("Certificate files of instance {} changed; reloading them and trying once more", instance.id());
            TlsBuilder.InstanceTls reloaded = TlsBuilder.build(instance.tls(), instance.baseDir(), "instance " + instance.id() + " tls");
            client = client(instance, reloaded);
            clients.put(key, client);
            try {
                response = attempt(instance, request, client, publicOperation);
            } catch (SSLException again) {
                throw tlsError(instance, again);
            }
        }
        if (response.statusCode() == 401 && !publicOperation && instance.authType().equals("oauth2ClientCredentials")) {
            tokens.discard(instance);
            try {
                response = attempt(instance, request, client, false);
            } catch (SSLException e) {
                throw tlsError(instance, e);
            }
        }
        return response;
    }

    private HttpResponse<byte[]> attempt(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, Client client,
                                         boolean publicOperation) throws SSLException {
        URI uri = request.uri();
        HttpRequest.Builder http = HttpRequest.newBuilder().timeout(Duration.ofSeconds(instance.timeoutSeconds()));
        String cookie = null;
        for (Map.Entry<String, String> header : request.headers()) {
            String name = header.getKey().toLowerCase();
            if (name.equals("cookie")) {
                cookie = header.getValue();
            } else if (!RESERVED.contains(name)) {
                http.header(header.getKey(), header.getValue());
            }
        }

        JsonNode auth = instance.auth();
        switch (publicOperation ? "none" : instance.authType()) {
            case "basic" -> http.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (auth.path("username").asText() + ":" + auth.path("password").asText()).getBytes(StandardCharsets.UTF_8)));
            case "bearer" -> http.header("Authorization", "Bearer " + auth.path("token").asText());
            case "oauth2ClientCredentials" -> http.header("Authorization", "Bearer " + tokens.token(instance, client.http()));
            case "apiKey" -> {
                JsonNode placement = apiKeys.get(instance.api());
                if (placement == null) {
                    throw new ToolError("INTERNAL", "this server doesn't know where to send the API key");
                }
                String name = placement.path("name").asText();
                String value = auth.path("value").asText();
                switch (placement.path("in").asText("header")) {
                    case "query" -> uri = URI.create(uri + (uri.getRawQuery() == null ? "?" : "&")
                            + URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                    case "cookie" -> cookie = (cookie == null ? "" : cookie + "; ") + name + "=" + value;
                    default -> http.header(name, value);
                }
            }
            case "none" -> {
            }
            default -> throw new ToolError("AUTH_DOWNSTREAM", "sign-in type " + instance.authType() + " is not supported by this runtime");
        }
        if (cookie != null) {
            http.header("Cookie", cookie);
        }
        if (request.body() != null) {
            http.header("Content-Type", request.contentType());
            http.method(request.method(), HttpRequest.BodyPublishers.ofByteArray(request.body()));
        } else {
            http.method(request.method(), HttpRequest.BodyPublishers.noBody());
        }
        http.uri(uri);

        try {
            return client.http().send(http.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", instance.id() + " did not accept a connection within " + instance.timeoutSeconds() + " s",
                    "Check that the instance's baseUrl is reachable from the server");
        } catch (HttpTimeoutException e) {
            throw new ToolError("UPSTREAM_TIMEOUT", instance.id() + " did not answer within " + instance.timeoutSeconds() + " s",
                    "Try again, or narrow the request");
        } catch (SSLException e) {
            throw e;
        } catch (ConnectException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", instance.id() + " refused the connection",
                    "Check that the instance's baseUrl is reachable from the server");
        } catch (IOException e) {
            if (e.getCause() instanceof SSLException ssl) {
                throw ssl;
            }
            throw new ToolError("UPSTREAM_UNREACHABLE", "Request to " + instance.id() + " failed: " + e.getClass().getSimpleName(),
                    "Check the instance's address and network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolError("INTERNAL", "the call was interrupted");
        }
    }

    private static ToolError tlsError(RuntimeConfig.Instance instance, SSLException e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new ToolError("UPSTREAM_UNREACHABLE", "TLS connection to " + instance.id() + " failed: " + reason,
                "Check the instance's tls settings (ca, trust, client certificate); the diagnostics pack can fetch the server's certificate");
    }

    private static Client client(RuntimeConfig.Instance instance, TlsBuilder.InstanceTls tls) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(instance.timeoutSeconds(), 30)))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null) {
            builder.sslContext(tls.context());
        }
        return new Client(builder.build(), tls);
    }
}
