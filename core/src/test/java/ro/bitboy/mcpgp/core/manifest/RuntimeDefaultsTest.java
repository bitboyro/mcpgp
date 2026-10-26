package ro.bitboy.mcpgp.core.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The runtimeDefaults the platform's Access control and Observability pages edit, as they reach the manifest. */
class RuntimeDefaultsTest {

    @TempDir
    Path dir;

    Compiler.Result compile(String runtimeDefaults) throws Exception {
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
                apis: [ { id: notes, contracts: [ { version: "1.0", spec: notes.yaml } ] } ]
                runtimeDefaults:
                %s
                """.formatted(runtimeDefaults.indent(2)));
        return new Compiler(new Schemas()).compile(dir.resolve("notes.mcpgp.yaml"), Compiler.Options.defaults());
    }

    @Test
    void accessAndObservabilityDefaultsAreShipped() throws Exception {
        Compiler.Result result = compile("""
                inboundAuth: { allowed: [static, oauth], default: oauth }
                audit: { file: { enabled: true, rotate: "size:50", retainDays: 90 }, arguments: hashed }
                telemetry: { enabled: true, exporter: otlp, serviceName: notes, signals: [metrics] }
                configuration:
                  sessions: { enabled: true }
                  logging: { level: warn }
                  server: { auth: { scopes: { notes.read: { access: read-only }, notes.admin: { tools: ["notes_*"] } } } }
                """);
        assertThat(result.manifest()).as("%s", result.diagnostics()).isPresent();
        JsonNode m = result.manifest().get();
        assertThat(m.at("/runtime/inboundAuth/default").asText()).isEqualTo("oauth");
        assertThat(m.at("/runtime/inboundAuth/allowed")).extracting(JsonNode::asText).containsExactly("static", "oauth");
        JsonNode defaults = m.at("/policies/runtimeDefaults");
        assertThat(defaults.at("/audit/file/rotate").asText()).isEqualTo("size:50");
        assertThat(defaults.at("/telemetry/signals/0").asText()).isEqualTo("metrics");
        assertThat(defaults.at("/configuration/sessions/enabled").asBoolean()).isTrue();
        assertThat(defaults.at("/configuration/logging/level").asText()).isEqualTo("warn");
        assertThat(defaults.at("/configuration/server/auth/scopes/notes.admin/tools/0").asText()).isEqualTo("notes_*");
    }

    @Test
    void aShippedDefaultTheRuntimeWouldRefuseIsAnError() throws Exception {
        assertThat(compile("configuration: { logging: { level: verbose } }").manifest()).isEmpty();
        assertThat(compile("configuration: { server: { auth: { scopes: { x: { access: some } } } } }").manifest()).isEmpty();
    }
}
