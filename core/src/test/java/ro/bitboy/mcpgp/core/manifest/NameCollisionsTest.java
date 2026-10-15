package ro.bitboy.mcpgp.core.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Two operations that would get the same tool name (naming.md §6): an error that suggests a name, or method-path. */
class NameCollisionsTest {

    @TempDir
    Path dir;

    Compiler.Result compile(String naming) throws Exception {
        Path project = dir.resolve("p" + System.nanoTime());
        Files.createDirectories(project);
        // the same operationId twice, as specs copied by hand often have
        Files.writeString(project.resolve("tc.yaml"), """
                openapi: 3.0.3
                info: { title: Trading Configuration, version: "1.0" }
                paths:
                  /partners/{identifier}:
                    put:
                      operationId: partnerIdentifierPut
                      parameters: [ { name: identifier, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: ok } }
                  /tests/{identifier}:
                    put:
                      operationId: partnerIdentifierPut
                      parameters: [ { name: identifier, in: path, required: true, schema: { type: string } } ]
                      responses: { "200": { description: ok } }
                """);
        Files.writeString(project.resolve("tc.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: tc-mcp }
                apis: [ { id: tc, toolPrefix: tc, contracts: [ { version: "1.0", spec: tc.yaml } ] } ]
                %s
                """.formatted(naming));
        return new Compiler(new Schemas()).compile(project.resolve("tc.mcpgp.yaml"), Compiler.Options.defaults());
    }

    @Test
    void theErrorSuggestsANameAndMethodPathResolvesIt() throws Exception {
        Compiler.Result clash = compile("");
        assertThat(clash.manifest()).isEmpty();
        assertThat(clash.diagnostics()).filteredOn(d -> d.level() == Diagnostic.Level.ERROR).singleElement()
                .satisfies(d -> assertThat(d.message()).contains("tc_test_replace").contains("naming.collisions: method-path"));

        Compiler.Result resolved = compile("naming: { collisions: method-path }");
        assertThat(resolved.manifest()).as("%s", resolved.diagnostics()).isPresent();
        assertThat(resolved.manifest().get().path("tools").findValuesAsText("name")).contains("tc_partner_identifier_put", "tc_test_replace");
        assertThat(resolved.manifest().get().toString()).contains("\"step\":\"collision\"");
    }
}
