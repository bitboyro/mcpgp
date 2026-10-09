package ro.bitboy.mcpgp.schemas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every example and conformance file must match its schema; every file must be classified. */
class RepositoryFilesValidationTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private final Schemas schemas = new Schemas();

    @Test
    void allExampleAndConformanceFilesAreValid() throws IOException {
        List<String> failures = new ArrayList<>();
        int validated = 0;
        for (Path file : files()) {
            String relative = RepositoryFiles.relative(ROOT, file);
            RepositoryFiles.Classification c = RepositoryFiles.classify(relative);
            switch (c.decision()) {
                case UNKNOWN -> failures.add(relative + ": not classified; add a rule to RepositoryFiles");
                case SKIP -> { }
                case VALIDATE -> {
                    validated++;
                    for (String problem : schemas.validateFile(c.kind().orElseThrow(), file)) {
                        failures.add(relative + " [" + c.reason() + "] " + problem);
                    }
                }
            }
        }
        assertThat(failures).as("schema violations").isEmpty();
        assertThat(validated).as("number of validated files").isGreaterThan(15);
    }

    private static List<Path> files() throws IOException {
        List<Path> result = new ArrayList<>();
        for (String dir : List.of("examples", "conformance")) {
            Path base = ROOT.resolve(dir);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).sorted().forEach(result::add);
            }
        }
        return result;
    }
}
