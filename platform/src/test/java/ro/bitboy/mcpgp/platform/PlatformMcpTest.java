package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.runtime.mcp.McpServerFactory;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's own MCP server, as agents get it: downloaded from the platform, run with the agent's
 * token, and calling the platform API, whose scopes and guardrails apply as to any client.
 */
class PlatformMcpTest extends PlatformTestBase {

    @TempDir
    Path dir;

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    /** An agent with a role, and its token. */
    static String agent(String role) throws Exception {
        JsonNode agent = json(send(bearer("POST", "/api/v1/identities", JSON.writeValueAsBytes(Map.of("kind", "agent",
                "name", role + "-bot-" + System.nanoTime(), "role", role)))));
        return json(send(bearer("POST", "/api/v1/tokens", JSON.writeValueAsBytes(Map.of("identity", agent.path("id").asText())))))
                .path("value").asText();
    }

    McpServerFactory server(String token) throws Exception {
        HttpResponse<byte[]> download = send(bearer("GET", "/api/v1/platform-mcp", null));
        assertThat(download.statusCode()).isEqualTo(200);
        Map<String, byte[]> files = unzip(download.body());
        assertThat(files).containsKeys("mcpgp-platform.jar", "manifest.json", "docs/TOOLS.md");
        Path manifest = dir.resolve("manifest.json");
        Files.write(manifest, files.get("manifest.json"));
        Manifest platform = Manifest.file(manifest);
        Path config = dir.resolve("mcpgp-platform.yaml");
        Files.writeString(config, """
                instance:
                  baseUrl: "%s"
                  auth: { type: bearer, token: "${env:MCPGP_TOKEN}" }
                """.formatted(root));
        RuntimeConfig runtime = new ConfigLoader(new Schemas()).load(platform, new ConfigLoader.Options(Optional.of(config), dir,
                Map.of("MCPGP_TOKEN", token), Map.of("storage.root", dir.resolve("data").toString())));
        return new McpServerFactory(platform, runtime);
    }

    static JsonNode call(McpServerFactory server, String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = server.call(null, new McpSchema.CallToolRequest(tool, arguments, null));
        assertThat(result.isError()).as("%s: %s", tool, result.content()).isFalse();
        return JSON.valueToTree(result.structuredContent());
    }

    @Test
    void agentsGetThePlatformApiAsToolsButNotPeoplesSignIn() throws Exception {
        McpServerFactory server = server(agent("builder"));
        List<String> tools = server.catalog().all().keySet().stream().toList();
        assertThat(tools).contains("platform_server_list", "platform_version_add", "platform_server_edit", "platform_build_start",
                "platform_manifest_get", "platform_explain_tool");
        assertThat(tools).doesNotContain("platform_sign_in", "platform_sign_out", "platform_password_set", "platform_store_export",
                "platform_store_import");
        // what takes things away from people asks first (docs/guides/agents.md)
        for (String tool : List.of("platform_build_delete", "platform_build_archive", "platform_server_archive", "platform_token_revoke",
                "platform_request_reject")) {
            assertThat(server.catalog().tool(tool).orElseThrow().path("confirm").asBoolean()).as(tool).isTrue();
        }
        assertThat(server.catalog().tool("platform_build_start").orElseThrow().path("confirm").asBoolean()).isFalse();
        assertThat(tools.stream().filter(t -> t.startsWith("platform_")).count()).isBetween(40L, 65L);
        // agents can start a server from a spec, and add a spec version, like people on the pages
        assertThat(tools.stream().filter(t -> t.contains("spec") || t.contains("contract")).toList()).as(tools.toString()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void anAgentLooksAtServersAndStartsABuild() throws Exception {
        McpServerFactory server = server(agent("builder"));
        assertThat(call(server, "platform_server_list", Map.of()).path("items").findValuesAsText("id")).contains("orders-mcp");
        JsonNode manifest = call(server, "platform_manifest_get", Map.of("path", Map.of("id", "orders-mcp", "version", "latest")));
        assertThat(manifest.at("/server/name").asText()).isEqualTo("orders-mcp");
        JsonNode build = call(server, "platform_build_start", Map.of("path", Map.of("id", "orders-mcp"), "body", Map.of()));
        assertThat(build.path("status").asText()).isIn("queued", "running", "failed", "succeeded");
        // the platform records the agent, not the person whose server it is
        JsonNode activity = json(send(bearer("GET", "/api/v1/activity?action=build.start&limit=1", null)));
        assertThat(activity.at("/items/0/actorKind").asText()).isEqualTo("agent");
    }

    @Test
    void thePlatformsRulesStillApply() throws Exception {
        McpServerFactory viewer = server(agent("viewer"));
        McpSchema.CallToolResult refused = viewer.call(null, new McpSchema.CallToolRequest("platform_build_start",
                Map.of("path", Map.of("id", "orders-mcp"), "body", Map.of()), null));
        assertThat(refused.isError()).isTrue();
        assertThat(JSON.valueToTree(refused.structuredContent()).toString()).contains("403");
    }
}
