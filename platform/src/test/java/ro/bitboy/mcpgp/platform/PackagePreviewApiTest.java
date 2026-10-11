package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a build of a version will contain, before building it (spec/platform-api.md §2.1). */
class PackagePreviewApiTest extends PlatformTestBase {

    @Test
    void formatsFilesAndDocsOfTheNextBuild() throws Exception {
        JsonNode preview = json(send(bearer("GET", "/api/v1/servers/orders-mcp/versions/latest/package", null)));
        assertThat(preview.path("formats").findValuesAsText("id")).containsExactly("jar", "mcpb", "docker");
        assertThat(preview.path("formats").findValuesAsText("file")).contains("orders-mcp.jar", "docker/docker-compose.yml");
        assertThat(preview.path("files").toString()).contains("docs/INSTALL.md").contains("SHA256SUMS");
        assertThat(preview.at("/docs/docs~1INSTALL.md").asText()).contains("orders-mcp");
        assertThat(preview.path("zip").asText()).startsWith("orders-mcp-").endsWith(".zip");
        assertThat(preview.has("signed")).isTrue();
        assertThat(preview.path("version").asInt()).isPositive();
    }
}
