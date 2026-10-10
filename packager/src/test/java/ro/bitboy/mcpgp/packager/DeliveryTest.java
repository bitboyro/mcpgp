package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.config.ConfigLoader;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** The desktop bundle and the container files (packaging.md §7.2, §7.3). */
class DeliveryTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path ORDERS = Path.of("..", "examples", "manifest", "orders-mcp.manifest.json").toAbsolutePath().normalize();

    @TempDir
    Path dir;

    static ObjectNode orders() throws Exception {
        return (ObjectNode) JSON.readTree(ORDERS.toFile());
    }

    /** What a desktop client does with the bundle's mcp_config: fill in ${…}. */
    static Map<String, String> substitute(JsonNode env, Map<String, String> values) {
        Map<String, String> out = new HashMap<>();
        Pattern variable = Pattern.compile("\\$\\{([^}]+)}");
        env.properties().forEach(e -> {
            Matcher m = variable.matcher(e.getValue().asText());
            StringBuilder text = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(text, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
            }
            m.appendTail(text);
            out.put(e.getKey(), text.toString());
        });
        return out;
    }

    @Test
    void aSingleInstanceBundleAsksForTheTargetAndItsSignInAndConfiguresTheRuntime() throws Exception {
        ObjectNode manifest = orders();
        manifest.with("instances").put("mode", "single");
        ObjectNode bundle = Bundle.manifest(manifest);
        assertThat(bundle.path("manifest_version").asText()).isEqualTo("0.3");
        assertThat(bundle.at("/server/type").asText()).isEqualTo("binary");
        assertThat(bundle.at("/server/mcp_config/command").asText()).isEqualTo("java");
        assertThat(bundle.at("/server/mcp_config/args")).extracting(JsonNode::asText).containsExactly("-jar", "${__dirname}/server/orders-mcp.jar");
        assertThat(bundle.path("user_config").properties()).extracting(Map.Entry::getKey).containsExactly("base_url", "username", "password");
        assertThat(bundle.at("/user_config/password/sensitive").asBoolean()).isTrue();
        assertThat(bundle.at("/user_config/username/sensitive").asBoolean()).isFalse();

        // the environment the client starts the server with is a valid runtime config
        Path file = dir.resolve("orders-mcp.manifest.json");
        Files.writeString(file, manifest.toString());
        Map<String, String> env = substitute(bundle.at("/server/mcp_config/env"), Map.of("HOME", dir.toString(),
                "user_config.base_url", "https://orders.example.com", "user_config.username", "svc", "user_config.password", "s3cret"));
        RuntimeConfig config = new ConfigLoader(new Schemas()).load(Manifest.file(file), new ConfigLoader.Options(Optional.empty(), dir, env, Map.of()));
        RuntimeConfig.Instance instance = config.allInstances().getFirst();
        assertThat(instance.baseUrl()).isEqualTo("https://orders.example.com");
        assertThat(instance.authType()).isEqualTo("basic");
        assertThat(instance.auth().path("username").asText()).isEqualTo("svc");
        assertThat(env.get("ORDERS_MCP_STORAGE__ROOT")).isEqualTo(dir + "/.orders-mcp");
    }

    @Test
    void severalInstancesNeedAConfigFile() throws Exception {
        ObjectNode bundle = Bundle.manifest(orders());
        assertThat(bundle.at("/user_config/config/type").asText()).isEqualTo("file");
        assertThat(bundle.at("/server/mcp_config/args")).extracting(JsonNode::asText).endsWith("--config", "${user_config.config}");
        // the tools a client shows are those in tools/list (hybrid: pinned and the rest found by search)
        List<String> tools = new ArrayList<>();
        bundle.path("tools").forEach(t -> tools.add(t.path("name").asText()));
        assertThat(tools).contains("search_tools", "orders_customer_get").doesNotContain("orders_customer_create");
        assertThat(bundle.path("tools")).allSatisfy(t -> assertThat(t.path("description").asText()).isNotBlank());
    }

    @Test
    void theContainerRunsOverHttpAsANonRootUser() throws Exception {
        List<String> warnings = new ArrayList<>();
        JsonNode definition = JSON.readTree("{\"build\":{\"image\":{\"base\":\"registry.acme.internal/ubi9/openjdk-21-runtime\",\"tar\":true}}}");
        Map<String, String> files = Container.files(orders(), definition, warnings);
        String dockerfile = files.get("docker/Dockerfile");
        assertThat(dockerfile).contains("FROM registry.acme.internal/ubi9/openjdk-21-runtime", "COPY orders-mcp.jar /app/orders-mcp.jar",
                "ORDERS_MCP_SERVER__TRANSPORT=http", "ORDERS_MCP_SERVER__HOST=0.0.0.0", "ORDERS_MCP_STORAGE__ROOT=/data", "USER 10001:10001",
                "\"--config\", \"/config/orders-mcp.yaml\"");
        assertThat(files.get("docker/docker-compose.yml")).contains("dockerfile: docker/Dockerfile", "- ../config:/config:ro");
        assertThat(warnings).singleElement().asString().contains("push and tar are not available");

        ObjectNode stdioOnly = orders();
        stdioOnly.with("runtime").with("transports").putArray("allowed").add("stdio");
        List<String> more = new ArrayList<>();
        assertThat(Container.files(stdioOnly, JSON.createObjectNode(), more)).isEmpty();
        assertThat(more).singleElement().asString().contains("needs the http transport");
    }
}
