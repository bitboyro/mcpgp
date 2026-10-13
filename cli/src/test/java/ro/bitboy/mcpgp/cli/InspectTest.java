package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** diff, preview, try and migrate (cli.md §3.5–3.8). */
class InspectTest {

    static final Path EXAMPLES = Path.of("..", "examples").toAbsolutePath().normalize();
    static final Path ORDERS = EXAMPLES.resolve("platform/orders/orders.mcpgp.yaml");
    static final String WORKSPACE = EXAMPLES.resolve("platform/workspace").toString();
    static final ObjectMapper JSON = new ObjectMapper();

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    int run(String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    /** The orders example's manifest as built now, with an extra tool the current build doesn't have. */
    Path baselineWithExtraTool(Path dir) throws Exception {
        assertThat(run("build", "--workspace", WORKSPACE, "--manifest-only", "--out", dir.toString(), ORDERS.toString())).as(err.toString()).isZero();
        Path manifest;
        try (var files = Files.list(dir)) {
            manifest = files.filter(f -> f.toString().endsWith(".manifest.json")).findFirst().orElseThrow();
        }
        ObjectNode root = (ObjectNode) JSON.readTree(manifest.toFile());
        ObjectNode extra = ((ArrayNode) root.path("tools")).addObject();
        extra.put("name", "orders_legacy_export").put("kind", "api").putObject("operation").put("key", "orders:GET:/legacy/export");
        JSON.writeValue(manifest.toFile(), root);
        return manifest;
    }

    @Test
    void diffAgainstAManifestOrAReleaseFolder(@TempDir Path dir) throws Exception {
        Path baseline = baselineWithExtraTool(dir.resolve("dist"));
        assertThat(run("diff", "--workspace", WORKSPACE, "--against", baseline.toString(), ORDERS.toString())).as(err.toString()).isZero();
        assertThat(out.toString()).contains("1 change since").contains("1 breaking").contains("orders_legacy_export: tool orders_legacy_export removed");

        // a release's dist/ folder; markdown for a pull request; and failing on breaking changes
        assertThat(run("diff", "--workspace", WORKSPACE, "--against", dir.resolve("dist").toString(), "--format", "md", "--fail-on-breaking",
                ORDERS.toString())).isEqualTo(6);
        assertThat(out.toString()).startsWith("### orders-mcp: ").contains("**Breaking");
        assertThat(run("diff", "--workspace", WORKSPACE, "--against", baseline.toString(), "--format", "json", ORDERS.toString())).isZero();
        JsonNode json = JSON.readTree(out.toString());
        assertThat(json.at("/0/breaking").asInt()).isEqualTo(1);
        assertThat(json.at("/0/changes/0/kind").asText()).isEqualTo("tool.removed");
    }

    static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", dir.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as(output).isZero();
    }

    @Test
    void diffAgainstTheLastReleaseTag(@TempDir Path dir) throws Exception {
        Path repo = dir.resolve("repo");
        Files.createDirectories(repo.resolve("spec"));
        Files.writeString(repo.resolve("spec/notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get: { operationId: listNotes, responses: { "200": { description: ok } } }
                    post: { operationId: addNote, responses: { "201": { description: ok } } }
                """);
        Path definition = Files.writeString(repo.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: spec/notes.yaml } ] } ]
                """);
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "first");
        git(repo, "tag", "v1.0.0");
        // leave the list operation out: its tool goes
        Files.writeString(definition, Files.readString(definition).replace("spec: spec/notes.yaml } ]",
                "spec: spec/notes.yaml } ], select: { exclude: [ { operationId: listNotes } ] }"));

        assertThat(run("diff", definition.toString())).as(err.toString()).isZero();
        assertThat(out.toString()).contains("notes-mcp: 1 change since 1.0.0, 1 breaking").contains("notes_note_list: tool notes_note_list removed (GET /notes)");
    }

    @Test
    void previewTryAndMigrate() throws Exception {
        assertThat(run("preview", "--workspace", WORKSPACE, ORDERS.toString())).as(err.toString()).isZero();
        assertThat(out.toString()).contains("## What the agent sees").contains("`orders_customer_get`").contains("## What the customer configures")
                .contains("instances:");
        assertThat(run("preview", "--workspace", WORKSPACE, "--tools", "--format", "json", ORDERS.toString())).isZero();
        assertThat(JSON.readTree(out.toString()).path("tools").findValuesAsText("name")).contains("orders_customer_get");

        assertThat(run("try", "--workspace", WORKSPACE, "--mock", "--tool", "orders_order_refund", "--args",
                "{\"path\": {\"id\": \"o-1\"}, \"body\": {\"amount\": 5}}", ORDERS.toString())).as(out + "\n" + err).isZero();
        assertThat(out.toString()).contains("\"status\"").contains("→ POST /api/v2/orders/o-1/refund");
        assertThat(run("try", "--workspace", WORKSPACE, ORDERS.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("--target");

        assertThat(run("migrate", ORDERS.toString())).isZero();
        assertThat(out.toString()).contains("already mcpgp/v1");
        assertThat(run("--no-color", "migrate", ORDERS.toString())).isZero();
    }

    @Test
    void tryCallsARealTarget() throws Exception {
        com.sun.net.httpserver.HttpServer target = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        target.createContext("/", x -> {
            seen.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
            byte[] body = "{\"id\":\"c-1\",\"name\":\"Ada\"}".getBytes();
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        target.start();
        try {
            String url = "http://127.0.0.1:" + target.getAddress().getPort();
            assertThat(run("try", "--workspace", WORKSPACE, "--target", url, "--tool", "orders_customer_get", "--args",
                    "{\"path\": {\"id\": \"c-1\"}}", ORDERS.toString())).as(out + "\n" + err).isZero();
            assertThat(out.toString()).contains("Ada");
            assertThat(seen).containsExactly("GET /api/v2/customers/c-1");
        } finally {
            target.stop(0);
        }
    }
}
