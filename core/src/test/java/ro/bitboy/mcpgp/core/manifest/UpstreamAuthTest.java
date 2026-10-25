package ro.bitboy.mcpgp.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** upstreamAuth in the manifest: credentials from the request when the definition allows them (auth.md §2.4). */
class UpstreamAuthTest {

    @TempDir
    Path dir;

    Compiler.Result compile(String upstreamAuth) throws Exception {
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
                """.formatted(upstreamAuth));
        return new Compiler(new Schemas()).compile(project.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
    }

    @Test
    void fromRequestIsCarriedWithItsHeader() throws Exception {
        Compiler.Result result = compile(", upstreamAuth: { fromRequest: { header: X-Notes-Token } }");
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        assertThat(result.manifest().get().at("/apis/0/upstreamAuth/fromRequest/header").asText()).isEqualTo("X-Notes-Token");
        assertThat(result.manifest().get().at("/apis/0/upstreamAuth/supported/0").asText()).isEqualTo("bearer");

        Compiler.Result off = compile("");
        assertThat(off.manifest().get().at("/apis/0/upstreamAuth").has("fromRequest")).isFalse();
        assertThat(compile(", upstreamAuth: { fromRequest: { header: \"bad header\" } }").manifest()).isEmpty();
    }

    @Test
    void theNameBefore040StillCompilesWithAWarning() throws Exception {
        Compiler.Result result = compile(", downstreamAuth: { fromRequest: { header: X-Notes-Token } }");
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        assertThat(result.manifest().get().at("/apis/0/upstreamAuth/fromRequest/header").asText()).isEqualTo("X-Notes-Token");
        assertThat(result.manifest().get().at("/apis/0").has("downstreamAuth")).isFalse();
        assertThat(result.diagnostics()).filteredOn(d -> d.code().equals(Codes.DEPRECATED_KEY))
                .singleElement().satisfies(d -> {
                    assertThat(d.level()).isEqualTo(Diagnostic.Level.WARNING);
                    assertThat(d.message()).contains("upstreamAuth");
                    assertThat(d.location().pointer()).isEqualTo("/apis/0/downstreamAuth");
                });
    }

    @Test
    void bothNamesOnOneApiAreRefused() throws Exception {
        Compiler.Result result = compile(", upstreamAuth: { supported: [bearer] }, downstreamAuth: { supported: [basic] }");
        assertThat(result.manifest()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.level()).isEqualTo(Diagnostic.Level.ERROR);
            assertThat(d.message()).contains("both upstreamAuth and downstreamAuth");
        });
    }
}
