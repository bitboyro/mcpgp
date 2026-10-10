package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks tool arguments (audit-and-errors §3, {@code INPUT_VALIDATION}): against the tool's served
 * input schema, then against the selected instance's contract, where properties marked
 * {@code x-mcpgp-contracts} exist only in some versions (tools-contract §4).
 */
final class InputValidator {

    private static final int MAX_MESSAGES = 5;

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    void validate(JsonNode tool, JsonNode arguments, String contract, String instance) {
        String name = tool.path("name").asText();
        Schema schema = schemas.computeIfAbsent(name, n -> registry.getSchema(tool.path("inputSchema")));
        List<String> problems = new ArrayList<>();
        List<Error> errors = schema.validate(arguments);
        // a failed oneOf also reports every branch that doesn't apply; keep the specific errors when there are some
        boolean specific = errors.stream().anyMatch(e -> !"oneOf".equals(e.getKeyword()) && !"additionalProperties".equals(e.getKeyword()));
        for (Error error : errors) {
            boolean noise = "oneOf".equals(error.getKeyword()) || "additionalProperties".equals(error.getKeyword()) && insideOneOf(error);
            if (specific && noise) {
                continue;
            }
            problems.add(location(error) + error.getMessage());
        }
        if (problems.isEmpty()) {
            JsonNode properties = tool.at("/inputSchema/properties");
            for (String group : List.of("path", "query", "header", "cookie", "body")) {
                JsonNode values = arguments.path(group);
                JsonNode members = properties.path(group).path("properties");
                values.fieldNames().forEachRemaining(field -> {
                    JsonNode marks = members.path(field).path("x-mcpgp-contracts");
                    if (marks.isArray() && !contains(marks, contract)) {
                        problems.add(group + "." + field + " is not available on instance " + instance
                                + " (contract " + contract + ")");
                    }
                });
            }
        }
        if (!problems.isEmpty()) {
            List<String> shown = problems.size() > MAX_MESSAGES ? problems.subList(0, MAX_MESSAGES) : problems;
            String more = problems.size() > MAX_MESSAGES ? " (and " + (problems.size() - MAX_MESSAGES) + " more)" : "";
            throw new ToolError("INPUT_VALIDATION", String.join("; ", shown) + more,
                    "Check the arguments against the tool's input schema");
        }
    }

    /** {@code /body/document} → {@code body.document: } */
    private static String location(Error error) {
        String at = error.getInstanceLocation().toString().replaceFirst("^\\$", "");
        return at.isEmpty() || at.equals("/") ? "" : at.replaceFirst("^[/.]", "").replace('/', '.') + ": ";
    }

    private static boolean insideOneOf(Error error) {
        return error.getEvaluationPath() != null && error.getEvaluationPath().toString().contains("oneOf");
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (item.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
