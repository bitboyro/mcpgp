package ro.bitboy.mcpgp.runtime.call;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/** A target server for tests: records every request and answers with queued responses (default 200 {}). */
public final class MockTarget implements AutoCloseable {

    /** What the target received. */
    public record Received(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> String.join(", ", e.getValue())).findFirst().orElse(null);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** Multipart parts as (headers, body) pairs. */
        public List<Part> parts() {
            String type = header("Content-Type");
            String boundary = "--" + type.substring(type.indexOf("boundary=") + "boundary=".length());
            List<Part> parts = new ArrayList<>();
            String all = new String(body, StandardCharsets.ISO_8859_1);
            for (String chunk : all.split(java.util.regex.Pattern.quote(boundary))) {
                if (chunk.isBlank() || chunk.startsWith("--")) {
                    continue;
                }
                String content = chunk.substring(2, chunk.length() - 2);    // CRLF after boundary, CRLF before next
                int split = content.indexOf("\r\n\r\n");
                parts.add(new Part(content.substring(0, split), content.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1)));
            }
            return parts;
        }
    }

    public record Part(String headers, byte[] body) {
        public String header(String name) {
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                    return line.substring(name.length() + 1).strip();
                }
            }
            return null;
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public record Response(int status, Map<String, String> headers, byte[] body, long delayMillis) {
        public static Response json(int status, String json) {
            return new Response(status, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8), 0);
        }
    }

    private final HttpServer server;
    public final List<Received> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Response> queue = new ConcurrentLinkedDeque<>();
    /**
     * Version requests (contract detection, runtime-config §4.4), kept apart from {@link #received} and
     * answered from their own queue (default 404), as a target routes them to their own endpoint.
     */
    public final List<Received> versionRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<Response> versions = new ConcurrentLinkedDeque<>();

    public MockTarget() throws IOException {
        this(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
    }

    /**
     * An HTTPS target with the given PKCS#12 keystore ({@link ro.bitboy.mcpgp.runtime.TestCerts#PASSWORD});
     * with {@code clientCa} set, clients must present a certificate it signed (mTLS).
     */
    public static MockTarget https(java.nio.file.Path keystore, java.nio.file.Path clientCa) throws Exception {
        java.security.KeyStore keys = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(keystore)) {
            keys.load(in, ro.bitboy.mcpgp.runtime.TestCerts.PASSWORD.toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, ro.bitboy.mcpgp.runtime.TestCerts.PASSWORD.toCharArray());
        javax.net.ssl.TrustManager[] trust = null;
        if (clientCa != null) {
            java.security.KeyStore cas = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            cas.load(null, null);
            try (var in = java.nio.file.Files.newInputStream(clientCa)) {
                cas.setCertificateEntry("client", java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(cas);
            trust = tmf.getTrustManagers();
        }
        javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), trust, null);
        com.sun.net.httpserver.HttpsServer server = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean needClientAuth = clientCa != null;
        server.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(context) {
            @Override
            public void configure(com.sun.net.httpserver.HttpsParameters params) {
                javax.net.ssl.SSLParameters ssl = context.getDefaultSSLParameters();
                ssl.setNeedClientAuth(needClientAuth);
                params.setSSLParameters(ssl);
            }
        });
        return new MockTarget(server);
    }

    private MockTarget(HttpServer server) {
        this.server = server;
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), Map.copyOf(exchange.getRequestHeaders()), body);
            Response response;
            if (request.path().endsWith("/version")) {
                versionRequests.add(request);
                response = versions.isEmpty() ? Response.json(404, "{}") : versions.poll();
            } else {
                received.add(request);
                response = queue.isEmpty() ? Response.json(200, "{}") : queue.poll();
            }
            if (response.delayMillis() > 0) {
                try {
                    Thread.sleep(response.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            if (response.body().length > 0) {
                exchange.getResponseBody().write(response.body());
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String address() {
        return (server instanceof com.sun.net.httpserver.HttpsServer ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The answer to the next version request. */
    public MockTarget respondToVersion(Response response) {
        versions.add(response);
        return this;
    }

    public MockTarget respond(Response response) {
        queue.add(response);
        return this;
    }

    public Received last() {
        return received.get(received.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
