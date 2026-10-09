package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MainTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final Path CONFORMANCE = Path.of("..", "conformance", "cases").toAbsolutePath().normalize();
    static final String ORDERS = EXAMPLES.resolve("orders/orders.mcpgp.yaml").toString();
    static final String WORKSPACE = EXAMPLES.resolve("workspace").toString();

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    int run(String... args) {
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    @AfterEach
    void resetEnv() {
        Main.env = System.getenv();
    }

    @Test
    void validatePassesWithWarnings() {
        assertThat(run("validate", "--workspace", WORKSPACE, ORDERS)).isZero();
        assertThat(out.toString()).contains("warning PACK_UNKNOWN").contains("ok, 11 API tools");
    }

    @Test
    void validateFailsOnErrors() throws IOException {
        Path definition = CONFORMANCE.resolve("resolve/path-escapes-root/definition/escape.mcpgp.yaml");
        assertThat(run("validate", "--json", definition.toString())).isEqualTo(1);
        JsonNode diagnostics = new ObjectMapper().readTree(out.toString());
        assertThat(diagnostics).anySatisfy(d -> assertThat(d.path("code").asText()).isEqualTo("PATH_OUTSIDE_ROOT"));
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertThat(run("validate", "--nope", ORDERS)).isEqualTo(2);
    }

    @Test
    void buildWritesTheManifest(@TempDir Path dist) throws IOException {
        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400");
        assertThat(run("build", "--manifest-only", "--workspace", WORKSPACE, "--build-number", "3",
                "--out", dist.toString(), ORDERS)).isZero();
        Path manifest = dist.resolve("orders-mcp-2.0.0+3.manifest.json");
        assertThat(manifest).exists();
        JsonNode json = new ObjectMapper().readTree(Files.readString(manifest));
        assertThat(json.at("/build/createdAt").asText()).isEqualTo("2026-10-09T10:40:00Z");
        assertThat(json.at("/server/version").asText()).isEqualTo("2.0.0+3");
    }

    @Test
    void buildWithoutManifestOnlyIsNotAvailableYet(@TempDir Path dist) {
        assertThat(run("build", "--workspace", WORKSPACE, "--out", dist.toString(), ORDERS)).isEqualTo(1);
        assertThat(err.toString()).contains("--manifest-only");
    }
}
