package ro.bitboy.mcpgp.core.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.resolve.DefinitionResolver;
import ro.bitboy.mcpgp.core.resolve.Documents;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the resolution part of every conformance case: cases that expect diagnostics must produce
 * them; cases that expect a manifest must resolve without errors. (Manifest comparison comes with the
 * rest of the compiler.)
 */
class ConformanceResolveTest {

    private static final Path CASES = Path.of("..", "conformance", "cases").toAbsolutePath().normalize();

    private final DefinitionResolver resolver = new DefinitionResolver(new Schemas());

    @TestFactory
    Stream<DynamicTest> cases() throws IOException {
        List<Path> caseFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(CASES)) {
            walk.filter(p -> p.getFileName().toString().equals("case.yaml")).sorted().forEach(caseFiles::add);
        }
        assertThat(caseFiles).isNotEmpty();
        return caseFiles.stream().map(caseFile -> DynamicTest.dynamicTest(
                CASES.relativize(caseFile.getParent()).toString(), () -> run(caseFile.getParent())));
    }

    private void run(Path caseDir) throws IOException {
        JsonNode spec = Documents.YAML.readTree(caseDir.resolve("case.yaml").toFile());
        Path definition = rootDefinition(caseDir.resolve("definition"));
        var result = resolver.resolve(definition, Workspace.none());

        if (spec.path("expect").asText().equals("diagnostics")) {
            JsonNode expected = Documents.JSON.readTree(caseDir.resolve("expected/diagnostics.json").toFile());
            for (JsonNode want : expected) {
                assertThat(result.diagnostics())
                        .as("expected %s", want)
                        .anySatisfy(actual -> matches(actual, want));
            }
            if (hasLevel(expected, "error")) {
                assertThat(result.definition()).isEmpty();
            }
        } else {
            assertThat(result.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).isEmpty();
            assertThat(result.definition()).isPresent();
        }
    }

    private static void matches(Diagnostic actual, JsonNode want) {
        assertThat(actual.level().name().toLowerCase()).isEqualTo(want.path("level").asText());
        assertThat(actual.code()).isEqualTo(want.path("code").asText());
        if (want.has("location")) {
            assertThat(actual.location().file()).isEqualTo(want.at("/location/file").asText());
            if (want.at("/location").has("pointer")) {
                assertThat(actual.location().pointer()).isEqualTo(want.at("/location/pointer").asText());
            }
        }
        if (want.has("messageContains")) {
            assertThat(actual.message()).contains(want.path("messageContains").asText());
        }
    }

    private static boolean hasLevel(JsonNode expected, String level) {
        for (JsonNode d : expected) {
            if (d.path("level").asText().equals(level)) {
                return true;
            }
        }
        return false;
    }

    private static Path rootDefinition(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".mcpgp.yaml"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No *.mcpgp.yaml in " + dir));
        }
    }
}
