package ro.bitboy.mcpgp.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** downstreamAuth in the manifest: credentials from the request when the definition allows them (auth.md §2.4). */
class DownstreamAuthTest {

    @TempDir
    Path dir;

    Compiler.Result compile(String downstreamAuth) throws Exception {
        Path project = dir.resolve("p" + System.nanoTime());
        Files.createDirectories(project);
        Files.writeString(project.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                components: { securitySchemes: { token: { type: http, scheme: bearer } } }
                security: [ { token: [] } ]
                paths:
                  /notes:
                    get: { operationId: listNotes, responses: { "200": { description: ok } } }
                """);
        Files.writeString(project.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ]%s } ]
                """.formatted(downstreamAuth));
        return new Compiler(new Schemas()).compile(project.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
    }

    @Test
    void fromRequestIsCarriedWithItsHeader() throws Exception {
        Compiler.Result result = compile(", downstreamAuth: { fromRequest: { header: X-Notes-Token } }");
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        assertThat(result.manifest().get().at("/apis/0/downstreamAuth/fromRequest/header").asText()).isEqualTo("X-Notes-Token");
        assertThat(result.manifest().get().at("/apis/0/downstreamAuth/supported/0").asText()).isEqualTo("bearer");

        Compiler.Result off = compile("");
        assertThat(off.manifest().get().at("/apis/0/downstreamAuth").has("fromRequest")).isFalse();
        assertThat(compile(", downstreamAuth: { fromRequest: { header: \"bad header\" } }").manifest()).isEmpty();
    }
}
