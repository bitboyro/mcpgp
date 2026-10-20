package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt templates from the definition (tools-contract §14.2): {@code prompts/list}, {@code prompts/get}
 * with Mustache-style {@code {{argument}}} placeholders (a missing required argument is an error, a
 * missing optional one renders empty), and {@code completion/complete} for arguments with an enum.
 */
final class Prompts {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final int MAX_COMPLETIONS = 100;

    private final Map<String, JsonNode> prompts = new LinkedHashMap<>();

    Prompts(Manifest manifest) {
        manifest.root().path("prompts").forEach(p -> prompts.put(p.path("name").asText(), p));
    }

    boolean any() {
        return !prompts.isEmpty();
    }

    List<McpSchema.Prompt> list() {
        List<McpSchema.Prompt> list = new ArrayList<>();
        prompts.values().forEach(p -> {
            List<McpSchema.PromptArgument> arguments = new ArrayList<>();
            p.path("arguments").forEach(a -> arguments.add(new McpSchema.PromptArgument(a.path("name").asText(), a.path("description").asText(null),
                    a.path("required").asBoolean(false))));
            list.add(new McpSchema.Prompt(p.path("name").asText(), p.path("description").asText(null), arguments));
        });
        return list;
    }

    /** Prompts with an argument that declares an enum: those offer completion. */
    List<String> completable() {
        List<String> names = new ArrayList<>();
        prompts.forEach((name, p) -> {
            for (JsonNode a : p.path("arguments")) {
                if (a.path("enum").isArray()) {
                    names.add(name);
                    return;
                }
            }
        });
        return names;
    }

    McpSchema.GetPromptResult get(McpSchema.GetPromptRequest request) {
        JsonNode prompt = prompts.get(request.name());
        if (prompt == null) {
            throw invalid("No prompt named '" + request.name() + "'");
        }
        Map<String, Object> given = request.arguments() == null ? Map.of() : request.arguments();
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode a : prompt.path("arguments")) {
            String name = a.path("name").asText();
            Object value = given.get(name);
            if (value == null || value.toString().isEmpty()) {
                if (a.path("required").asBoolean(false)) {
                    throw invalid("The prompt " + request.name() + " needs the argument " + name);
                }
                values.put(name, "");
                continue;
            }
            if (a.path("enum").isArray() && !contains(a.path("enum"), value.toString())) {
                throw invalid(name + " must be one of " + a.path("enum"));
            }
            values.put(name, value.toString());
        }
        List<McpSchema.PromptMessage> messages = new ArrayList<>();
        for (JsonNode m : prompt.path("messages")) {
            McpSchema.Role role = m.path("role").asText("user").equals("assistant") ? McpSchema.Role.ASSISTANT : McpSchema.Role.USER;
            messages.add(new McpSchema.PromptMessage(role, new McpSchema.TextContent(render(m.path("content").asText(), values))));
        }
        return new McpSchema.GetPromptResult(prompt.path("description").asText(null), messages);
    }

    McpSchema.CompleteResult complete(McpSchema.CompleteRequest request) {
        String promptName = request.ref() instanceof McpSchema.PromptReference ref ? ref.name() : null;
        JsonNode prompt = promptName == null ? null : prompts.get(promptName);
        List<String> values = new ArrayList<>();
        if (prompt != null && request.argument() != null) {
            for (JsonNode a : prompt.path("arguments")) {
                if (a.path("name").asText().equals(request.argument().name())) {
                    a.path("enum").forEach(v -> values.add(v.asText()));
                }
            }
        }
        return result(values, request.argument() == null ? null : request.argument().value());
    }

    /** The candidates that start with what the user has typed (any case), at most {@value #MAX_COMPLETIONS} of them. */
    static McpSchema.CompleteResult result(List<String> candidates, String typedValue) {
        String typed = typedValue == null ? "" : typedValue.toLowerCase(Locale.ROOT);
        List<String> values = candidates.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        List<String> shown = values.subList(0, Math.min(MAX_COMPLETIONS, values.size()));
        return new McpSchema.CompleteResult(new McpSchema.CompleteResult.CompleteCompletion(List.copyOf(shown), values.size(),
                values.size() > shown.size()));
    }

    static String render(String template, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean contains(JsonNode values, String value) {
        for (JsonNode v : values) {
            if (v.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static McpError invalid(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
    }
}
