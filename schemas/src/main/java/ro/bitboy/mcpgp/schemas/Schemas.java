package ro.bitboy.mcpgp.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.AbsoluteIri;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.path.PathType;
import com.networknt.schema.resource.InputStreamSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Validates mcpgp files against their JSON Schemas.
 *
 * <p>Schemas reference each other by {@code $id} under {@link #BASE_URI}; those URIs are mapped to the
 * classpath, so validation never touches the network.
 */
public final class Schemas {

    public static final String BASE_URI = "https://bitboyro.github.io/mcpgp/schemas/";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SchemaRegistry registry;
    private final Map<SchemaKind, Schema> cache = new EnumMap<>(SchemaKind.class);

    public Schemas() {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .pathType(PathType.JSON_POINTER)
                .build();
        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder -> builder
                .schemaRegistryConfig(config)
                .resourceLoaders(loaders -> loaders.values(list -> list.add(0, Schemas::bundled))));
    }

    /**
     * Our schemas, read through this module's class loader. Not a {@code classpath:} mapping: the library reads those
     * through the calling thread's class loader, and a pooled thread can carry one of a web server that has since
     * stopped, which then refuses every lookup.
     */
    private static InputStreamSource bundled(AbsoluteIri iri) {
        String id = iri.toString();
        if (!id.startsWith(BASE_URI)) {
            return null;
        }
        String path = "schemas/" + id.substring(BASE_URI.length());
        ClassLoader loader = Schemas.class.getClassLoader();
        return loader.getResource(path) == null ? null : () -> loader.getResourceAsStream(path);
    }

    /** One schema violation: a JSON pointer into the document and a message. */
    public record Problem(String pointer, String message) implements Comparable<Problem> {
        @Override
        public int compareTo(Problem other) {
            return Comparator.comparing(Problem::pointer).thenComparing(Problem::message).compare(this, other);
        }

        @Override
        public String toString() {
            return (pointer.isEmpty() ? "/" : pointer) + ": " + message;
        }
    }

    /** Validates a parsed document; returns the violations sorted by location, empty when valid. */
    public synchronized List<Problem> problems(SchemaKind kind, JsonNode document) {
        Schema schema = cache.computeIfAbsent(kind, k -> registry.getSchema(SchemaLocation.of(k.id())));
        List<Problem> problems = new ArrayList<>();
        for (Error error : schema.validate(document)) {
            problems.add(new Problem(error.getInstanceLocation().toString(), error.getMessage()));
        }
        problems.sort(Comparator.naturalOrder());
        return problems;
    }

    /** Validates a parsed document; returns human-readable problems, empty when valid. */
    public List<String> validate(SchemaKind kind, JsonNode document) {
        return problems(kind, document).stream().map(Problem::toString).toList();
    }

    /**
     * Validates a file. YAML and JSON files are validated as one document; {@code .jsonl} files are
     * validated line by line, and problems are prefixed with the line number.
     */
    public List<String> validateFile(SchemaKind kind, Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.endsWith(".jsonl")) {
            List<String> problems = new ArrayList<>();
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                for (String problem : validate(kind, JSON.readTree(line))) {
                    problems.add("line " + (i + 1) + " " + problem);
                }
            }
            return problems;
        }
        ObjectMapper mapper = name.endsWith(".json") ? JSON : YAML;
        JsonNode document = mapper.readTree(file.toFile());
        if (document == null || document.isMissingNode()) {
            return List.of("$: file is empty");
        }
        return validate(kind, document);
    }
}
