package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code mcpgp push} (cli.md §3): uploads a definition project to a platform as its server's next version,
 * and optionally builds and releases it there. Only the files the definition uses are sent.
 */
final class Push {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    /** A platform answer that isn't what the command needs. */
    static final class PlatformException extends IOException {
        PlatformException(String message) {
            super(message);
        }
    }

    private final URI api;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    /** @param platform the platform's address, https (http only for loopback) */
    Push(String platform, String token) {
        URI uri = URI.create(platform.endsWith("/") ? platform : platform + "/");
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && LOOPBACK.contains(uri.getHost()))) {
            throw new IllegalArgumentException("the platform address must be https (plain http only for localhost): " + platform);
        }
        this.api = uri.resolve("api/v1/");
        this.token = token;
    }

    /**
     * The project as the platform stores it: the definition file at the top, every file it uses under its
     * folder (overrides, contracts, overlays) and the lock file. Files outside the folder (workspace
     * references) are resolved on the platform from its library.
     */
    static Map<String, byte[]> files(ResolvedDefinition definition) throws IOException {
        Path root = definition.root().toAbsolutePath().normalize();
        java.util.Set<Path> used = new java.util.LinkedHashSet<>();
        used.add(definition.file());
        used.addAll(definition.files());
        definition.contracts().values().forEach(c -> {
            used.add(c.spec());
            used.addAll(c.overlays());
        });
        used.add(root.resolve("mcp-names.lock"));
        Map<String, byte[]> files = new TreeMap<>();
        for (Path file : used) {
            Path absolute = file.toAbsolutePath().normalize();
            if (absolute.startsWith(root) && Files.isRegularFile(absolute)) {
                files.put(root.relativize(absolute).toString().replace('\\', '/'), Files.readAllBytes(absolute));
            }
        }
        return files;
    }

    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry entry = new ZipEntry(f.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /**
     * What agents get from a manifest, without what differs between builds of the same definition (the
     * {@code build} section and the server version's build number): equal for the CLI's and the platform's
     * build when both use the same compiler.
     */
    static String contentDigest(JsonNode manifest) {
        ObjectNode copy = manifest.deepCopy();
        copy.remove("build");
        if (copy.path("server") instanceof ObjectNode server) {
            server.remove("version");
        }
        return CanonicalJson.digest(CanonicalJson.compact(copy).getBytes(StandardCharsets.UTF_8));
    }

    /** Uploads the project; creates the server when the platform doesn't have it. Returns the server and whether it is new. */
    Map.Entry<JsonNode, Boolean> upload(String server, String title, String message, byte[] zip) throws IOException {
        HttpResponse<String> existing = send(get("servers/" + encode(server)));
        String query = "?message=" + encode(message);
        if (existing.statusCode() == 404) {
            HttpResponse<String> created = send(post("servers?id=" + encode(server) + "&title=" + encode(title) + "&message=" + encode(message),
                    "application/zip", zip));
            return Map.entry(expect(created, 201), true);
        }
        expect(existing, 200);
        HttpResponse<String> added = send(post("servers/" + encode(server) + "/versions" + query, "application/zip", zip));
        return Map.entry(expect(added, 200, 201), false);
    }

    JsonNode startBuild(String server, int version) throws IOException {
        byte[] body = JSON.writeValueAsBytes(Map.of("version", String.valueOf(version)));
        return expect(send(post("servers/" + encode(server) + "/builds", "application/json", body)), 202);
    }

    /** Polls the build until it is done, or the timeout passes. */
    JsonNode awaitBuild(String build, Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            JsonNode state = expect(send(get("builds/" + encode(build))), 200);
            String status = state.path("status").asText();
            if (status.equals("succeeded") || status.equals("failed")) {
                return state;
            }
            if (System.nanoTime() > deadline) {
                throw new PlatformException("build " + build + " is still " + status + " after " + timeout.toSeconds() + " s");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PlatformException("interrupted");
            }
        }
    }

    String buildLog(String build) throws IOException {
        HttpResponse<String> log = send(get("builds/" + encode(build) + "/log"));
        return log.statusCode() == 200 ? log.body() : "";
    }

    /** The manifest the platform built. */
    JsonNode builtManifest(JsonNode build) throws IOException {
        for (JsonNode artifact : build.path("artifacts")) {
            String name = artifact.path("name").asText();
            if (name.endsWith(".manifest.json")) {
                return expect(send(get("builds/" + encode(build.path("id").asText()) + "/artifacts/" + encode(name))), 200);
            }
        }
        throw new PlatformException("build " + build.path("id").asText() + " has no manifest artifact");
    }

    /** Releases the build; the answer is the build (released) or, when a person must approve, the approval. */
    JsonNode release(String build) throws IOException {
        return expect(send(post("builds/" + encode(build) + "/release", "application/json", new byte[0])), 200, 202);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                .header("User-Agent", "mcpgp-cli/" + Compiler.VERSION);
    }

    private HttpRequest get(String path) {
        return request(path).GET().build();
    }

    private HttpRequest post(String path, String type, byte[] body) {
        return request(path).header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("interrupted");
        } catch (IOException e) {
            throw new PlatformException("cannot reach " + api.resolve("/") + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static JsonNode expect(HttpResponse<String> response, int... statuses) throws IOException {
        for (int status : statuses) {
            if (response.statusCode() == status) {
                return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
            }
        }
        String detail = response.body();
        try {
            JsonNode problem = JSON.readTree(response.body());
            detail = problem.path("detail").asText(problem.path("title").asText(detail));
        } catch (IOException e) {
            // not a problem document
        }
        String hint = switch (response.statusCode()) {
            case 401 -> " (is MCPGP_TOKEN a valid, unexpired token?)";
            case 403 -> " (the token lacks the scope for this; pipelines need servers:write, builds:run and builds:release)";
            default -> "";
        };
        throw new PlatformException(response.request().method() + " " + response.request().uri().getPath() + ": HTTP "
                + response.statusCode() + (detail.isBlank() ? "" : ": " + detail) + hint);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
