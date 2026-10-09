package ro.bitboy.mcpgp.schemas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

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

    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<SchemaKind, JsonSchema> cache = new EnumMap<>(SchemaKind.class);

    public Schemas() {
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(BASE_URI, "classpath:schemas/")));
        this.config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
    }

    /** Validates a parsed document; returns human-readable problems, empty when valid. */
    public synchronized List<String> validate(SchemaKind kind, JsonNode document) {
        JsonSchema schema = cache.computeIfAbsent(kind,
                k -> factory.getSchema(SchemaLocation.of(k.id()), config));
        List<String> problems = new ArrayList<>();
        for (ValidationMessage message : schema.validate(document)) {
            problems.add(message.getInstanceLocation() + ": " + message.getMessage());
        }
        problems.sort(Comparator.naturalOrder());
        return problems;
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
