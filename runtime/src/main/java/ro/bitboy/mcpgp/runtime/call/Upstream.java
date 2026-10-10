package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
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
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends requests to instances: one HTTP client per instance, downstream credentials added here
 * (never from tool input), redirects not followed (credentials must not travel to another host),
 * and no automatic retries (the agent decides).
 */
final class Upstream {

    /** Headers the agent may never set; the runtime owns them. */
    private static final Set<String> RESERVED = Set.of("authorization", "content-type", "content-length", "host",
            "cookie", "connection", "transfer-encoding", "expect", "upgrade");

    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    /** apiKey placement per API, from the manifest ({@code in}, {@code name}). */
    private final Map<String, JsonNode> apiKeys;

    Upstream(Map<String, JsonNode> apiKeys) {
        this.apiKeys = apiKeys;
    }

    HttpResponse<byte[]> send(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request) {
        if (!instance.tls().isEmpty()) {
            // never fall back to a looser trust than configured
            throw new ToolError("UPSTREAM_UNREACHABLE", "instance " + instance.id() + " has tls settings, which this runtime version doesn't apply yet",
                    "Remove the tls block to use the system trust store, or use a newer runtime");
        }
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

        // downstream credentials
        JsonNode auth = instance.auth();
        switch (instance.authType()) {
            case "basic" -> http.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (auth.path("username").asText() + ":" + auth.path("password").asText()).getBytes(StandardCharsets.UTF_8)));
            case "bearer" -> http.header("Authorization", "Bearer " + auth.path("token").asText());
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
            default -> throw new ToolError("AUTH_DOWNSTREAM", "sign-in type " + instance.authType()
                    + " is not available in this runtime version yet", "Configure basic, bearer or apiKey for instance " + instance.id());
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
            return client(instance).send(http.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpConnectTimeoutException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", instance.id() + " did not accept a connection within " + instance.timeoutSeconds() + " s",
                    "Check that the instance's baseUrl is reachable from the server");
        } catch (HttpTimeoutException e) {
            throw new ToolError("UPSTREAM_TIMEOUT", instance.id() + " did not answer within " + instance.timeoutSeconds() + " s",
                    "Try again, or narrow the request");
        } catch (SSLException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", "TLS connection to " + instance.id() + " failed: " + e.getMessage(),
                    "Check the instance's tls settings (ca, trust)");
        } catch (ConnectException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", instance.id() + " refused the connection",
                    "Check that the instance's baseUrl is reachable from the server");
        } catch (IOException e) {
            throw new ToolError("UPSTREAM_UNREACHABLE", "Request to " + instance.id() + " failed: " + e.getClass().getSimpleName(),
                    "Check the instance's address and network");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolError("INTERNAL", "the call was interrupted");
        }
    }

    private HttpClient client(RuntimeConfig.Instance instance) {
        return clients.computeIfAbsent(instance.api() + "/" + instance.id(), key -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(instance.timeoutSeconds(), 30)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }
}
