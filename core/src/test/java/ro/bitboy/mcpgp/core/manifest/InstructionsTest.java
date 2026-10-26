package ro.bitboy.mcpgp.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** server.instructions in the manifest: what MCP clients receive on initialize. */
class InstructionsTest {

    @TempDir
    Path dir;

    String instructions(String metadata, String more) throws Exception {
        Path project = dir.resolve("p" + System.nanoTime());
        Files.createDirectories(project);
        Files.writeString(project.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get: { operationId: listNotes, responses: { "200": { description: ok } } }
                """);
        Files.writeString(project.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata:
                  name: notes-mcp
                %s
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                %s
                """.formatted(metadata.indent(2), more));
        Compiler.Result result = new Compiler(new Schemas()).compile(project.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        return result.manifest().get().at("/server/instructions").asText(null);
    }

    @Test
    void instructionsAreKeptAsWritten() throws Exception {
        assertThat(instructions("""
                description: Notes for the team.
                instructions: |
                  Use these tools to read the team's notes.

                  - Search before listing.
                  - Never share notes outside the team.
                """, "")).isEqualTo("Use these tools to read the team's notes.\n\n- Search before listing.\n- Never share notes outside the team.");
    }

    @Test
    void withoutInstructionsTheDescriptionIsUsedOnOneLine() throws Exception {
        assertThat(instructions("description: |\n  Notes for\n  the team.\n", "")).isEqualTo("Notes for the team.");
        assertThat(instructions("title: Notes\n", "")).isNull();
        assertThat(instructions("description: Notes.\ninstructions: \"   \"\n", "")).isEqualTo("Notes.");
    }

    @Test
    void severalTargetServersAddTheInstanceNote() throws Exception {
        String note = "Every tool requires `instance`; call instances_list to see the configured servers.";
        assertThat(instructions("instructions: |\n  Read notes.\n\n  Search first.\n", "instances: { mode: multi }"))
                .isEqualTo("Read notes.\n\nSearch first.\n\n" + note);
        assertThat(instructions("instructions: Read notes.\n", "instances: { mode: multi }")).isEqualTo("Read notes. " + note);
    }
}
