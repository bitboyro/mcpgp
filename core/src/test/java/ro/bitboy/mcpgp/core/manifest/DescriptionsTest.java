package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Descriptions: rules for every kind of tool, shortening that keeps what rules add, and notices (definition-format §5). */
class DescriptionsTest {

    static final String LONG = "Lists the notes. " + "Every note has a long explanation. ".repeat(40);

    @TempDir
    Path dir;

    Compiler.Result compile(String extra) throws Exception {
        Path project = dir.resolve("p" + System.nanoTime());
        Files.createDirectories(project);
        Files.writeString(project.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get:
                      operationId: listNotes
                      tags: [notes]
                      summary: List notes
                      description: "%s"
                      responses: { "200": { description: ok } }
                    post:
                      operationId: createNote
                      tags: [writing]
                      summary: Create note
                      description: Creates a note.
                      responses: { "201": { description: ok } }
                """.formatted(LONG));
        Files.writeString(project.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                packs: { audit: {} }
                %s
                """.formatted(extra));
        return new Compiler(new Schemas()).compile(project.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
    }

    static Map<String, JsonNode> tools(Compiler.Result result) {
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        Map<String, JsonNode> tools = new LinkedHashMap<>();
        result.manifest().get().path("tools").forEach(t -> tools.put(t.path("name").asText(), t));
        return tools;
    }

    @Test
    void rulesReachPackBuiltInAndCustomToolsWhenTheySayWhichKind() throws Exception {
        Map<String, JsonNode> tools = tools(compile("""
                overrides:
                  rules:
                    - match: { kind: [pack, builtin, custom] }
                      description: { append: " Internal use only." }
                    - match: { tool: notes_audit_search }
                      title: { set: Search the audit }
                """));
        JsonNode audit = tools.values().stream().filter(t -> t.path("kind").asText().equals("pack")).findFirst().orElseThrow();
        assertThat(audit.path("description").asText()).endsWith(" Internal use only.");
        assertThat(audit.at("/provenance/description").findValuesAsText("step")).contains("rule");
        assertThat(tools.get("notes_note_create").path("description").asText()).isEqualTo("Creates a note.");   // API tools: not selected
    }

    @Test
    void aLongDescriptionLosesSpecTextNotWhatRulesAdd() throws Exception {
        Compiler.Result result = compile("""
                overrides:
                  rules:
                    - match: { tag: notes }
                      description: { append: "\\n\\nPolicy: notes may contain personal data." }
                """);
        String description = tools(result).get("notes_note_list").path("description").asText();
        assertThat(description).hasSizeLessThanOrEqualTo(1024).endsWith("Policy: notes may contain personal data.").startsWith("Lists the notes.");
        assertThat(description).contains("…");
        assertThat(result.diagnostics()).extracting(Diagnostic::code).contains("DESCRIPTION_TRUNCATED");
    }

    @Test
    void noticesGoOnTheToolsTheySelectAndAreNeverCut() throws Exception {
        Compiler.Result result = compile("""
                descriptions:
                  notices:
                    - { id: all, text: "Company policy: everything you do is audited." }
                    - { id: writes, text: "Changes need a ticket number.", position: prepend, match: { method: [POST, PUT, PATCH, DELETE] } }
                    - { id: chosen, text: "Ask before exporting.", match: { tool: [notes_note_list, notes_audit_search] } }
                    - { id: none, text: "Never shown.", match: { tag: missing } }
                """);
        Map<String, JsonNode> tools = tools(result);
        String create = tools.get("notes_note_create").path("description").asText();
        assertThat(create).isEqualTo("Changes need a ticket number.\n\nCreates a note.\n\nCompany policy: everything you do is audited.");
        String list = tools.get("notes_note_list").path("description").asText();
        assertThat(list).hasSizeLessThanOrEqualTo(1024).endsWith("Company policy: everything you do is audited.\n\nAsk before exporting.");
        assertThat(tools.values().stream().filter(t -> t.path("kind").asText().equals("pack")))
                .allSatisfy(t -> assertThat(t.path("description").asText()).contains("Company policy"));
        assertThat(tools.get("notes_note_create").at("/provenance/description").findValuesAsText("step")).contains("notice");
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals("NOTICE_MATCHES_NOTHING")).hasSize(1);
    }
}
