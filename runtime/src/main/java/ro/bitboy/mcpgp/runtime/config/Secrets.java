package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Secret handling (runtime-config §2): resolves {@code ${env:…}} and {@code ${file:…}} references,
 * rejects plain values in secret fields (unless {@code allowInlineSecrets}), and collects every resolved
 * secret value so it can be redacted wherever it would appear.
 */
final class Secrets {

    /**
     * @param config   the config with references replaced by their values
     * @param secrets  every secret value, for redaction
     * @param inline   secret fields that hold plain values (allowed only with {@code allowInlineSecrets})
     */
    record Result(ObjectNode config, Set<String> secrets, List<String> inline, List<String> problems) {
    }

    private static final Pattern REFERENCE = Pattern.compile("^\\$\\{(env|file|keychain|vault):([^}]+)}$");

    private final SchemaNav schema;
    private final Map<String, String> env;
    private final Path baseDir;

    /** @param baseDir where relative {@code ${file:…}} paths start (the config file's directory) */
    Secrets(SchemaNav schema, Map<String, String> env, Path baseDir) {
        this.schema = schema;
        this.env = env;
        this.baseDir = baseDir;
    }

    Result resolve(ObjectNode config, Set<String> fromEnv) {
        ObjectNode copy = config.deepCopy();
        Set<String> secrets = new TreeSet<>();
        List<String> inline = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        walk(copy, schema.root(), "", fromEnv, secrets, inline, problems);
        return new Result(copy, secrets, inline, problems);
    }

    private void walk(JsonNode node, JsonNode nodeSchema, String pointer, Set<String> fromEnv,
                      Set<String> secrets, List<String> inline, List<String> problems) {
        if (node instanceof ObjectNode object) {
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode child = object.get(key);
                JsonNode childSchema = schema.property(nodeSchema, key).orElse(JsonNodeFactory.instance.objectNode());
                String childPointer = pointer + "/" + key;
                if (child.isTextual()) {
                    object.set(key, JsonNodeFactory.instance.textNode(
                            value(child.asText(), childSchema, childPointer, fromEnv, secrets, inline, problems)));
                } else {
                    walk(child, childSchema, childPointer, fromEnv, secrets, inline, problems);
                }
            }
        } else if (node instanceof ArrayNode array) {
            JsonNode itemSchema = schema.items(nodeSchema);
            for (int i = 0; i < array.size(); i++) {
                String childPointer = pointer + "/" + i;
                if (array.get(i).isTextual()) {
                    array.set(i, JsonNodeFactory.instance.textNode(
                            value(array.get(i).asText(), itemSchema, childPointer, fromEnv, secrets, inline, problems)));
                } else {
                    walk(array.get(i), itemSchema, childPointer, fromEnv, secrets, inline, problems);
                }
            }
        }
    }

    private String value(String raw, JsonNode fieldSchema, String pointer, Set<String> fromEnv,
                         Set<String> secrets, List<String> inline, List<String> problems) {
        boolean secret = schema.isSecret(fieldSchema);
        Matcher reference = REFERENCE.matcher(raw);
        if (!reference.matches()) {
            if (secret) {
                if (!fromEnv.contains(pointer)) {
                    inline.add(pointer);
                }
                secrets.add(raw);
            }
            return raw;
        }
        String kind = reference.group(1);
        String target = reference.group(2);
        String resolved = switch (kind) {
            case "env" -> {
                String value = env.get(target);
                if (value == null) {
                    problems.add(pointer + ": environment variable " + target + " is not set");
                }
                yield value;
            }
            case "file" -> {
                Path file = baseDir.resolve(target).normalize();
                try {
                    yield Files.readString(file).strip();
                } catch (IOException e) {
                    problems.add(pointer + ": cannot read " + target);
                    yield null;
                }
            }
            default -> {
                problems.add(pointer + ": ${" + kind + ":…} references are not supported in this version");
                yield null;
            }
        };
        if (resolved == null) {
            return raw;
        }
        if (secret) {
            secrets.add(resolved);
        }
        return resolved;
    }
}
