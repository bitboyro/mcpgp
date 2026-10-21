package ro.bitboy.mcpgp.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.platform.api.OpenApiApi;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform's OpenAPI compiles with mcpgp itself, so the platform MCP server (M7) can be generated from it. */
class OwnOpenApiTest {

    @Test
    void compilesWithoutWarnings(@TempDir Path dir) throws IOException {
        // the platform MCP server's project as the jar bundles it (platform/mcp)
        for (String file : java.util.List.of("mcpgp-platform.mcpgp.yaml", "contracts/platform-api.openapi.yaml")) {
            try (InputStream in = OwnOpenApiTest.class.getResourceAsStream("/mcpgp/platform-mcp/" + file)) {
                Files.createDirectories(dir.resolve(file).getParent());
                Files.copy(in, dir.resolve(file));
            }
        }
        Compiler.Result result = new Compiler(new Schemas()).compile(dir.resolve("mcpgp-platform.mcpgp.yaml"), Compiler.Options.defaults());
        assertThat(result.diagnostics()).filteredOn(d -> d.level() != Diagnostic.Level.INFO).isEmpty();
        assertThat(result.manifest().orElseThrow().path("tools").findValuesAsText("name"))
                .contains("platform_server_list", "platform_server_create", "platform_version_add", "platform_manifest_get",
                        "platform_token_revoke", "platform_build_start", "platform_build_release", "platform_artifact_download",
                        "platform_server_duplicate", "platform_variant_create", "platform_library_item_create",
                        "platform_request_approve", "platform_server_edit", "platform_mcp_package_get")
                .doesNotContain("platform_sign_in", "platform_store_export");
        // validating and trying change nothing; adding a version does
        java.util.Map<String, Boolean> readOnly = new java.util.HashMap<>();
        for (var tool : result.manifest().orElseThrow().path("tools")) {
            String operation = tool.at("/operation/key").asText();
            if (operation.endsWith(":POST:/servers/{id}/versions/{version}/validate")) {
                readOnly.put("validate", tool.at("/annotations/readOnlyHint").asBoolean());
            } else if (operation.endsWith(":POST:/servers/{id}/try")
                    || operation.endsWith(":POST:/servers/{id}/versions/{version}/try")) {
                readOnly.put("try", tool.at("/annotations/readOnlyHint").asBoolean());
            } else if (operation.endsWith(":POST:/servers/{id}/versions")) {
                readOnly.put("add", tool.at("/annotations/readOnlyHint").asBoolean());
            }
        }
        assertThat(readOnly).containsEntry("validate", true).containsEntry("try", true).containsEntry("add", false);
    }
}
