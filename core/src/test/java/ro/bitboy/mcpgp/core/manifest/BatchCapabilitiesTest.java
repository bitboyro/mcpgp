package ro.bitboy.mcpgp.core.manifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BatchCapabilitiesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    @Test void compilerCarriesConfigurationAndRegistersSupportTools() throws Exception {
        java.nio.file.Files.writeString(dir.resolve("api.yaml"), """
                openapi: 3.0.3
                info: { title: Example, version: '1' }
                paths:
                  /status:
                    get:
                      operationId: getStatus
                      responses:
                        '200':
                          description: Status
                          content:
                            application/json:
                              schema: { type: object, properties: { status: { type: string } } }
                """);
        var definition = dir.resolve("server.mcpgp.yaml");
        java.nio.file.Files.writeString(definition, """
                apiVersion: mcpgp/v1
                kind: McpServer
                metadata: { name: batch-example }
                api:
                  id: example
                  contracts: [{ version: '1', spec: api.yaml }]
                exposure: { mode: discovery }
                batch: { enabled: true, maxCalls: 7, allowWrites: true }
                results: { mode: auto, ttlSeconds: 60, captureLimitKb: 2048 }
                """);
        var compiled = new ro.bitboy.mcpgp.core.Compiler(new ro.bitboy.mcpgp.schemas.Schemas())
                .compile(definition, ro.bitboy.mcpgp.core.Compiler.Options.defaults());
        assertThat(compiled.ok()).as(compiled.diagnostics().toString()).isTrue();
        var manifest = compiled.manifest().orElseThrow();
        assertThat(manifest.at("/policies/batch/maxCalls").asInt()).isEqualTo(7);
        assertThat(manifest.at("/policies/results/mode").asText()).isEqualTo("auto");
        assertThat(manifest.path("tools").findValuesAsText("handler")).contains("batch_call", "result_get", "call_tool");
        assertThat(new ro.bitboy.mcpgp.schemas.Schemas().validate(ro.bitboy.mcpgp.schemas.SchemaKind.MANIFEST, manifest)).isEmpty();
    }
    @Test void independentCapabilitiesDoNotDependOnDiscoveryMode() {
        var json = new ObjectMapper();
        var definition = json.createObjectNode();
        assertThat(Builtins.execution(definition)).isEmpty();
        definition.putObject("batch").put("enabled", true);
        assertThat(Builtins.execution(definition)).extracting(Builtins.Spec::base).containsExactly("batch_call");
        definition.putObject("results").put("mode", "auto");
        for (String mode : java.util.List.of("plain", "discovery", "hybrid", "toolsets")) {
            definition.withObject("/exposure").put("mode", mode);
            assertThat(Builtins.execution(definition)).extracting(Builtins.Spec::base).containsExactly("batch_call", "result_get");
        }
        definition.withObject("/batch").put("enabled", false);
        assertThat(Builtins.execution(definition)).extracting(Builtins.Spec::base).containsExactly("result_get");
    }
}
