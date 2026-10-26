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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends requests to instances: one HTTP client per instance with its own TLS context, downstream
 * credentials added here (never from tool input), redirects not followed (credentials must not travel
 * to another host). The only repeats are the ones the specs allow: once after a {@code 401} with a
 * fresh OAuth token (auth.md §2.1), once after a TLS failure when certificate files changed (§5.4),
 * and the JDK client's own: a GET or HEAD is sent a second time, once, when the target closed the
 * connection without answering (tools-contract §6; it can't be turned off). Its retry after a refused
 * connection is turned off.
 */
final class Upstream {

    static {
        HttpRetries.noConnectRetry();
    }

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
    private final TokenExchange exchanges = new TokenExchange(Clock.systemUTC());
    /** The most of an answer that is read; the rest is never downloaded. */
    private final int readLimit;

    Upstream(Map<String, JsonNode> apiKeys, Redactor redactor) {
        this(apiKeys, redactor, 16 * 1024 * 1024);
    }

    Upstream(Map<String, JsonNode> apiKeys, Redactor redactor, int readLimit) {
        this.apiKeys = apiKeys;
        this.tokens = new OAuthTokens(redactor, Clock.systemUTC());
        this.readLimit = readLimit;
    }

    /** The credential a call uses: an entry of the instance's {@code auth}, and for fromRequest the value the client sent. */
    record Credential(JsonNode entry, String value) {
        String type() {
            return entry.path("type").asText("none");
        }
    }

    /** Where {@code sent} carries the signed-in user's access token. */
    static final String SUBJECT = "\u0000subject";

    private static final Credential NONE = new Credential(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "none"), null);

    /**
     * The first entry of the instance's {@code auth} that can be used and satisfies the operation's
     * security (auth.md §2.2): a fromRequest entry when the client sent its header with this request
     * (§2.4), any other entry always.
     *
     * @param sent     the request's credential headers, by lower-case name, and under {@link #SUBJECT} the
     *                 signed-in user's access token (inbound oauth), for tokenExchange
     * @param security the operation's security requirements (variant {@code security})
     * @param kinds    the API's {@code upstreamAuth.schemes}
     */
    static Credential credential(RuntimeConfig.Instance instance, Map<String, String> sent, boolean publicOperation,
                                 JsonNode security, JsonNode kinds) {
        if (publicOperation) {
            return NONE;
        }
        List<String> expected = new java.util.ArrayList<>();
        boolean exchange = false;
        String unmet = null;
        for (JsonNode entry : instance.authEntries()) {
            String needs = Satisfaction.unmet(entry, instance, security, kinds);
            if (needs != null) {
                unmet = unmet == null ? needs : unmet;
                continue;
            }
            if (entry.path("type").asText().equals("tokenExchange")) {
                String subject = sent.get(SUBJECT);
                if (subject != null) {
                    return new Credential(entry, subject);
                }
                exchange = true;
                continue;
            }
            if (!entry.path("type").asText().equals("fromRequest")) {
                return new Credential(entry, null);
            }
            String header = entry.path("header").asText();
            String value = sent.get(header.toLowerCase(java.util.Locale.ROOT));
            if (value != null && !value.isBlank()) {
                if (value.length() > 16 * 1024 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                    throw new ToolError("AUTH_UPSTREAM", "the " + header + " header isn't a usable credential", "Check the value your MCP client sends");
                }
                return new Credential(entry, value.strip());
            }
            expected.add(header);
        }
        if (unmet != null && expected.isEmpty() && !exchange) {
            throw new ToolError("AUTH_UPSTREAM", instance.id() + "'s credentials can't call this operation: it needs " + unmet,
                    "Use another instance, or ask whoever runs this server for a credential that has it");
        }
        if (expected.isEmpty()) {
            throw new ToolError("AUTH_UPSTREAM", instance.id() + " calls the API as you: sign in to this server (OAuth) to use it",
                    "This server exchanges your sign-in for a token for the API; a request without it can't");
        }
        throw new ToolError("AUTH_UPSTREAM", instance.id() + " needs your credential in the " + String.join(" or ", expected) + " header"
                + (exchange ? ", or your sign-in to this server" : ""),
                "Set that header in your MCP client's configuration for this server (its value comes from your environment, not from the agent)");
    }

    /**
     * @param credential      from {@link #credential}
     * @param publicOperation the operation declares {@code security: []}: no credentials are sent
     */
    HttpResponse<byte[]> send(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, Credential credential, boolean publicOperation) {
        ro.bitboy.mcpgp.runtime.telemetry.Telemetry.Upstream traced = ro.bitboy.mcpgp.runtime.telemetry.Telemetry.current()
                .upstream(instance.api(), instance.id(), request.method());
        Integer status = null;
        try {
            HttpResponse<byte[]> response = sendUntraced(instance, request, publicOperation ? NONE : credential);
            status = response.statusCode();
            return response;
        } finally {
            traced.end(status);
        }
    }

    private HttpResponse<byte[]> sendUntraced(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, Credential credential) {
        String key = instance.api() + "/" + instance.id();
        Client client = clients.computeIfAbsent(key, k -> client(instance, instance.tlsContext()));
        HttpResponse<byte[]> response;
        try {
            response = attempt(instance, request, client, credential);
        } catch (SSLException e) {
            if (client.tls() == null || !client.tls().filesChanged()) {
                throw tlsError(instance, e);
            }
            LOG.info("Certificate files of instance {} changed; reloading them and trying once more", instance.id());
            TlsBuilder.InstanceTls reloaded = TlsBuilder.build(instance.tls(), instance.baseDir(), "instance " + instance.id() + " tls");
            client = client(instance, reloaded);
            clients.put(key, client);
            try {
                response = attempt(instance, request, client, credential);
            } catch (SSLException again) {
                throw tlsError(instance, again);
            }
        }
        if (response.statusCode() == 401 && (credential.type().equals("oauth2ClientCredentials") || credential.type().equals("tokenExchange"))) {
            if (credential.type().equals("tokenExchange")) {
                exchanges.discard(instance, credential.value());
            } else {
                tokens.discard(instance);
            }
            try {
                response = attempt(instance, request, client, credential);
            } catch (SSLException e) {
                throw tlsError(instance, e);
            }
        }
        return response;
    }

    private HttpResponse<byte[]> attempt(RuntimeConfig.Instance instance, RequestBuilder.UpstreamRequest request, Client client,
                                         Credential credential) throws SSLException {
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

        JsonNode auth = credential.entry();
        switch (credential.type()) {
            // the client's own credential, as it sent it, to this instance's address only (redirects aren't followed)
            case "fromRequest" -> http.header(auth.path("as").asText("Authorization"), credential.value());
            case "basic" -> http.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (auth.path("username").asText() + ":" + auth.path("password").asText()).getBytes(StandardCharsets.UTF_8)));
            case "bearer" -> http.header("Authorization", "Bearer " + auth.path("token").asText());
            case "oauth2ClientCredentials" -> http.header("Authorization", "Bearer " + tokens.token(instance, client.http()));
            // the user's identity: their token exchanged for one for this API (the user's own token never goes to the API)
            case "tokenExchange" -> http.header("Authorization", "Bearer " + exchanges.token(instance, auth, credential.value(), client.http()));
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
            default -> throw new ToolError("AUTH_UPSTREAM", "sign-in type " + credential.type() + " is not supported by this runtime");
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
            return CappedResponse.read(sendCancellable(client.http(), http.build()), readLimit);
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
            ro.bitboy.mcpgp.runtime.mcp.Inflight.Handle inflight = ro.bitboy.mcpgp.runtime.mcp.Inflight.current();
            if (inflight != null && inflight.cancelled()) {
                throw cancelled(inflight);
            }
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

    /**
     * Sends the request; when the client cancels the call being served ({@code notifications/cancelled}),
     * the request is aborted at once, and so is reading the answer.
     */
    private static HttpResponse<java.io.InputStream> sendCancellable(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        ro.bitboy.mcpgp.runtime.mcp.Inflight.Handle inflight = ro.bitboy.mcpgp.runtime.mcp.Inflight.current();
        if (inflight == null) {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        }
        java.util.concurrent.CompletableFuture<HttpResponse<java.io.InputStream>> pending =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        Runnable abort = () -> pending.cancel(true);
        inflight.onCancel(abort);
        try {
            HttpResponse<java.io.InputStream> response = pending.get();
            Runnable closeBody = () -> {
                try {
                    response.body().close();
                } catch (IOException e) {
                    LOG.debug("Closing a cancelled answer failed: {}", e.getMessage());
                }
            };
            inflight.onCancel(closeBody);
            return response;
        } catch (java.util.concurrent.CancellationException e) {
            throw cancelled(inflight);
        } catch (java.util.concurrent.ExecutionException e) {
            if (inflight.cancelled()) {
                throw cancelled(inflight);
            }
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause);
        } finally {
            inflight.forget(abort);
        }
    }

    static ToolError cancelled(ro.bitboy.mcpgp.runtime.mcp.Inflight.Handle inflight) {
        return new ToolError("CANCELLED", "The client cancelled this call" + (inflight.reason() == null ? "" : " (" + inflight.reason() + ")")
                + "; the request to the API was stopped, and it may already have taken effect there");
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
