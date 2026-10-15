package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Tools' output schemas (tools-contract §6, definition schemas.output): on by default, loose, lists as items. */
class OutputSchemasTest {

    @TempDir
    Path dir;

    JsonNode compile(String extra, Boolean override) throws Exception {
        Files.writeString(dir.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get:
                      operationId: listNotes
                      responses:
                        "200":
                          description: ok
                          content:
                            application/json:
                              schema: { type: array, items: { $ref: "#/components/schemas/Note" } }
                  /notes/{id}:
                    get:
                      operationId: getNote
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses:
                        "200": { description: ok, content: { application/json: { schema: { $ref: "#/components/schemas/Note" } } } }
                  /notes/{id}/file:
                    get:
                      operationId: getNoteFile
                      parameters: [ { name: id, in: path, required: true, schema: { type: string } } ]
                      responses:
                        "200": { description: ok, content: { application/pdf: { schema: { type: string, format: binary } } } }
                components:
                  schemas:
                    Note:
                      type: object
                      required: [id, text]
                      additionalProperties: false
                      properties: { id: { type: string }, text: { type: string } }
                """);
        Files.writeString(dir.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                """ + extra);
        Compiler.Result result = new Compiler(new Schemas()).compile(dir.resolve("notes.mcpgp.yaml"),
                new Compiler.Options(Workspace.none(), false, Compiler.Options.defaults().build(), override));
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        return result.manifest().get();
    }

    static JsonNode tool(JsonNode manifest, String suffix) {
        for (JsonNode tool : manifest.path("tools")) {
            if (tool.path("operation").path("operationId").asText().equals(suffix)) {
                return tool;
            }
        }
        throw new AssertionError(suffix);
    }

    @Test
    void toolsDescribeTheirResultLooselyByDefault() throws Exception {
        JsonNode manifest = compile("", null);
        JsonNode get = tool(manifest, "getNote").path("outputSchema");

        assertThat(get.path("type").asText()).isEqualTo("object");
        assertThat(get.at("/properties/text/type").asText()).isEqualTo("string");
        assertThat(get.has("required")).as("real answers may lack fields").isFalse();
        assertThat(get.has("additionalProperties")).as("or have more").isFalse();
        assertThat(manifest.at("/policies/outputSchemas").asBoolean()).isTrue();
    }

    @Test
    void aListIsDescribedAsItems() throws Exception {
        JsonNode list = tool(compile("", null), "listNotes").path("outputSchema");
        assertThat(list.path("type").asText()).isEqualTo("object");
        assertThat(list.at("/properties/items/type").asText()).isEqualTo("array");
        assertThat(list.at("/properties/items/items/properties").has("text")).isTrue();
    }

    @Test
    void aFileAnswerHasNoOutputSchema() throws Exception {
        assertThat(tool(compile("", null), "getNoteFile").path("outputSchema").isNull()).isTrue();
    }

    @Test
    void theDefinitionOrTheCliTurnsThemOff() throws Exception {
        JsonNode off = compile("schemas: { output: false }\n", null);
        assertThat(tool(off, "getNote").path("outputSchema").isNull()).isTrue();
        assertThat(off.at("/policies/outputSchemas").asBoolean()).isFalse();

        JsonNode cli = compile("", Boolean.FALSE);
        assertThat(tool(cli, "getNote").path("outputSchema").isNull()).isTrue();
    }
}
