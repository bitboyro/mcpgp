package ro.bitboy.mcpgp.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A suffix for every API tool's name (naming.md §2, namespace.suffix), and a rule renaming one tool. */
class NameSuffixTest {

    @TempDir
    Path dir;

    @Test
    void everyApiToolEndsWithTheSuffix() throws Exception {
        Files.writeString(dir.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get: { operationId: listNotes, responses: { "200": { description: ok } } }
                    post: { operationId: createNote, responses: { "201": { description: ok } } }
                """);
        Files.writeString(dir.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, toolPrefix: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                naming: { namespace: { server: acme, suffix: v2 } }
                overrides: { rules: [ { id: "ui:createNote", match: { operationId: createNote }, name: { set: note_add } } ] }
                """);
        Compiler.Result result = new Compiler(new Schemas()).compile(dir.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        assertThat(result.manifest().get().path("tools").findValuesAsText("name")).contains("acme_notes_note_list_v2", "acme_notes_note_add_v2");
    }
}
