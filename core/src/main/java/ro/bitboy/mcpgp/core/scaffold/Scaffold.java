package ro.bitboy.mcpgp.core.scaffold;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A new definition project around an API's spec, as {@code mcpgp init --from-spec} and the platform's
 * "new server from a spec" write it: a definition with one API, and a first test case file.
 */
public final class Scaffold {

    private static final Set<String> FILLER = Set.of("api", "apis", "the", "service", "rest", "openapi", "swagger", "v1", "v2", "v3");

    private Scaffold() {
    }

    /** An API id from the spec's title: "Orders API" → {@code orders}, "Customer Accounts" → {@code customer_accounts}. */
    public static String apiId(String title) {
        List<String> words = new ArrayList<>();
        for (String word : title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty() && !FILLER.contains(word)) {
                words.add(word);
            }
        }
        String id = String.join("_", words);
        if (id.length() > 32) {
            id = id.substring(0, 32).replaceAll("_+$", "");
        }
        if (!id.matches("[a-z][a-z0-9_]{1,31}")) {
            id = "api";
        }
        return id;
    }

    public static String definition(String name, String title, String api, String version, String contract) {
        return """
                # The definition of %1$s (docs/spec/definition-format.md). Check it with: mcpgp validate %1$s.mcpgp.yaml
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata:
                  name: %1$s
                  title: %2$s

                apis:
                  - id: %3$s
                    # tool names start with the prefix: %3$s_<noun>_<verb>
                    toolPrefix: %3$s
                    contracts:
                      - { version: %4$s, spec: %5$s }
                    # to leave operations out: select: { exclude: [ { tag: [internal] } ] }

                instances:
                  # multi: one server for many targets; agents pass `instance`
                  mode: single

                # names, descriptions, hidden parameters: overrides: { include: overrides/*.yaml }
                # safety: { confirmDestructive: true }
                """.formatted(name, yamlString(title), api, yamlString(version), contract);
    }

    /** Test cases for up to three read tools that need no arguments; a commented example otherwise. */
    public static String smoke(JsonNode manifest) {
        StringBuilder cases = new StringBuilder();
        int count = 0;
        for (JsonNode tool : manifest.path("tools")) {
            if (count == 3) {
                break;
            }
            boolean read = tool.at("/operation/key").asText().contains(":GET:");
            JsonNode required = tool.at("/inputSchema/required");
            boolean noArguments = required.isMissingNode() || required.isEmpty();
            if (tool.path("kind").asText().equals("api") && read && noArguments) {
                String name = tool.path("name").asText();
                cases.append("  - name: ").append(name).append(" works\n")
                        .append("    tool: ").append(name).append('\n')
                        .append("    args: {}\n")
                        .append("    expect: { ok: true }\n");
                count++;
            }
        }
        String header = """
                # Run by `mcpgp test` against a mock made from the spec's examples (docs/guides/ci-pipeline.md §5).
                # A case: name, tool, args, and expect: { ok: true } (optionally with structured: {...}) or { code: ERROR_CODE }.
                # In multi-instance builds, pass instance: mock.
                """;
        if (count == 0) {
            return header + """
                    cases: []
                    #  - name: get a customer
                    #    tool: crm_customer_get
                    #    args: { path: { id: "c-1" } }
                    #    expect: { ok: true }
                    """;
        }
        return header + "cases:\n" + cases;
    }

    private static String yamlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
