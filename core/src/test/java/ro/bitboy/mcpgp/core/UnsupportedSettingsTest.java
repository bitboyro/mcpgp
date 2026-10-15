package ro.bitboy.mcpgp.core;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Settings the format describes but this version doesn't do: the compiler names each one instead of ignoring it. */
class UnsupportedSettingsTest {

    @TempDir
    Path dir;

    Compiler.Result compile(String extra, String apiExtra) throws Exception {
        Files.writeString(dir.resolve("notes.yaml"), """
                openapi: 3.0.3
                info: { title: Notes, version: "1.0" }
                paths:
                  /notes:
                    get: { operationId: listNotes, responses: { "200": { description: ok } } }
                """);
        Files.writeString(dir.resolve("notes.mcpgp.yaml"), """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: notes-mcp }
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ]%s } ]
                %s
                """.formatted(apiExtra, extra));
        return new Compiler(new Schemas()).compile(dir.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "generation.mode: codegen | 'generation: { mode: codegen }' | /generation/mode",
            "generation.mode: both | 'generation: { mode: both }' | /generation/mode",
            "schemas.input: lean | 'schemas: { input: lean }' | /schemas/input",
            "build.jarSigning | 'build: { jarSigning: { keystore: k.p12, alias: a, password: \"${env:P}\" } }' | /build/jarSigning",
            "build.image.registry | 'build: { image: { registry: registry.acme.com } }' | /build/image/registry",
            "build.image.push | 'build: { image: { push: true } }' | /build/image/push",
            "build.image.tar | 'build: { image: { tar: true } }' | /build/image/tar",
    })
    void isRefusedWithItsName(String setting, String yaml, String pointer) throws Exception {
        Compiler.Result result = compile(yaml, "");
        assertThat(result.manifest()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.level()).isEqualTo(Diagnostic.Level.ERROR);
            assertThat(d.code()).isEqualTo("NOT_SUPPORTED_YET");
            assertThat(d.message()).startsWith(setting + " isn't supported yet");
            assertThat(d.location().pointer()).isEqualTo(pointer);
        });
    }

    @Test
    void paginationIsRefused() throws Exception {
        Compiler.Result result = compile("", ", pagination: { style: cursor }");
        assertThat(result.manifest()).isEmpty();
        assertThat(result.diagnostics()).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo("NOT_SUPPORTED_YET");
            assertThat(d.message()).startsWith("pagination isn't supported yet");
            assertThat(d.location().pointer()).isEqualTo("/apis/0/pagination");
        });
    }

    @Test
    void theSupportedValuesStillBuild() throws Exception {
        Compiler.Result result = compile("generation: { mode: runtime }\nschemas: { input: full }\nbuild: { image: { push: false } }", "");
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
    }
}
