package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.resolve.Workspace;
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

    @Test
    void sharedNoticesComeFromTheLibraryAndAServerCanReplaceOne() throws Exception {
        Path workspace = dir.resolve("workspace");
        Files.createDirectories(workspace.resolve("notices"));
        Files.writeString(workspace.resolve("mcpgp-workspace.yaml"), """
                workspace: acme
                notices: { company-policy: notices/company-policy.yaml }
                """);
        Files.writeString(workspace.resolve("notices/company-policy.yaml"), """
                title: Acme policy
                notices:
                  - id: data-policy
                    text: "Company policy: customer data stays in the EU."
                  - id: writes
                    text: "Changes need a ticket number."
                    match: { method: [POST, PUT, PATCH, DELETE] }
                """);
        String own = """
                descriptions:
                  include: mcpgp://acme/notices/company-policy@1
                  notices:
                    - id: writes
                      text: "Changes need an approved change request."
                      match: { method: [POST] }
                    - text: "Notes are kept for a year."
                """;
        Compiler.Result result = compileIn(workspace, own);
        Map<String, JsonNode> tools = tools(result);
        String create = tools.get("notes_note_create").path("description").asText();
        assertThat(create).isEqualTo("Creates a note.\n\nCompany policy: customer data stays in the EU.\n\n"
                + "Changes need an approved change request.\n\nNotes are kept for a year.");
        assertThat(tools.get("notes_note_list").path("description").asText()).doesNotContain("change request")
                .endsWith("Company policy: customer data stays in the EU.\n\nNotes are kept for a year.");
        // where each one came from: the shared one names its item, the replaced one is the server's own
        JsonNode steps = tools.get("notes_note_create").at("/provenance/description");
        assertThat(steps.findValues("from")).extracting(JsonNode::asText).containsExactly("mcpgp://acme/notices/company-policy@1");
        assertThat(result.manifest().get().toString()).doesNotContain("\"include\"");
    }

    @Test
    void aSetCanBeAFileOfTheProjectAndMustBeValid() throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project.resolve("policy"));
        Files.writeString(project.resolve("policy/notices.yaml"), "notices:\n  - id: eu\n    text: Data stays in the EU.\n");
        Compiler.Result result = compileAt(project, Workspace.none(), "descriptions: { include: [policy/notices.yaml] }");
        assertThat(tools(result).get("notes_note_list").path("description").asText()).endsWith("Data stays in the EU.");

        Files.writeString(project.resolve("policy/notices.yaml"), "notices:\n  - text: no id\n");
        Compiler.Result invalid = compileAt(project, Workspace.none(), "descriptions: { include: [policy/notices.yaml] }");
        assertThat(invalid.diagnostics()).anyMatch(d -> d.code().equals(ro.bitboy.mcpgp.core.diag.Codes.NOTICES_INVALID));
    }

    Compiler.Result compileIn(Path workspace, String extra) throws Exception {
        ro.bitboy.mcpgp.core.diag.Diagnostics diagnostics = new ro.bitboy.mcpgp.core.diag.Diagnostics();
        Workspace ws = Workspace.load(workspace.resolve("mcpgp-workspace.yaml"), new Schemas(), diagnostics);
        assertThat(diagnostics.all()).isEmpty();
        Path project = dir.resolve("p" + System.nanoTime());
        Files.createDirectories(project);
        return compileAt(project, ws, extra);
    }

    Compiler.Result compileAt(Path project, Workspace workspace, String extra) throws Exception {
        Files.writeString(project.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get:
                      operationId: listNotes
                      summary: List notes
                      description: Lists the notes.
                      responses: { "200": { description: ok } }
                    post:
                      operationId: createNote
                      summary: Create note
                      description: Creates a note.
                      responses: { "201": { description: ok } }
                """);
        Files.writeString(project.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                %s
                """.formatted(extra));
        return new Compiler(new Schemas()).compile(project.resolve("notes.mcpgp.yaml"), new Compiler.Options(workspace, false));
    }
}
