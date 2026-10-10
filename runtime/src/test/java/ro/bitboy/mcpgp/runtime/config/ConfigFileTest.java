package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** In-place edits of config files: comments and formatting outside the edited item stay. */
class ConfigFileTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir
    Path dir;

    static Map<String, Object> instance(String id) {
        Map<String, Object> instance = ConfigFile.map();
        instance.put("id", id);
        instance.put("baseUrl", "https://" + id + ".example/api");
        Map<String, Object> auth = ConfigFile.map();
        auth.put("type", "basic");
        auth.put("username", "svc");
        auth.put("password", "${env:" + id.toUpperCase().replace('-', '_') + "_PASSWORD}");
        instance.put("auth", auth);
        return instance;
    }

    @Test
    void addsAndRemovesYamlItemsKeepingComments() throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.copy(REPO.resolve("examples/client/multi-instance/orders-mcp.yaml"), file);
        String before = Files.readString(file);
        ConfigFile config = new ConfigFile(file);

        config.addInstance(List.of("instances"), instance("new-one"));
        String added = Files.readString(file);
        assertThat(added).contains("# TLS terminated by the reverse proxy").contains("# pinned; startup fails");
        JsonNode parsed = YAML.readTree(added);
        assertThat(parsed.path("instances")).hasSize(4);
        assertThat(parsed.at("/instances/3/id").asText()).isEqualTo("new-one");
        assertThat(parsed.at("/instances/3/auth/password").asText()).isEqualTo("${env:NEW_ONE_PASSWORD}");
        assertThat(parsed.at("/files/base64/maxKb").asInt()).isEqualTo(2048);         // what follows the list is intact
        assertThat(Files.readString(dir.resolve("orders-mcp.yaml.bak"))).isEqualTo(before);

        assertThat(config.removeInstance(List.of("instances"), "partner-x")).isTrue();
        JsonNode removed = YAML.readTree(Files.readString(file));
        assertThat(removed.path("instances").findValuesAsText("id")).containsExactly("prod-eu", "test", "new-one");
        assertThat(removed.at("/instances/1/tls/clientCert/path").asText()).isEqualTo("./certs/test-client.p12");
        assertThat(config.removeInstance(List.of("instances"), "nope")).isFalse();

        assertThat(config.removeInstance(List.of("instances"), "new-one")).isTrue();
        assertThat(config.removeInstance(List.of("instances"), "prod-eu")).isTrue();
        assertThat(YAML.readTree(Files.readString(file)).path("instances").findValuesAsText("id")).containsExactly("test");
    }

    @Test
    void handlesFlowItemsNewFilesAndJson() throws IOException {
        Path file = dir.resolve("a.yaml");
        Files.writeString(file, "# head\ninstances:\n  - { id: a, baseUrl: a.example }   # first\n  - { id: b, baseUrl: b.example }\naudit: { arguments: hashed }\n");
        ConfigFile config = new ConfigFile(file);
        config.addInstance(List.of("instances"), instance("c"));
        assertThat(config.removeInstance(List.of("instances"), "a")).isTrue();
        String text = Files.readString(file);
        assertThat(text).startsWith("# head\n").contains("audit: { arguments: hashed }");
        assertThat(YAML.readTree(text).path("instances").findValuesAsText("id")).containsExactly("b", "c");

        Path fresh = dir.resolve("new.yaml");
        new ConfigFile(fresh).addInstance(List.of("instances"), instance("x"));
        assertThat(YAML.readTree(Files.readString(fresh)).at("/instances/0/id").asText()).isEqualTo("x");

        Path json = dir.resolve("c.json");
        Files.writeString(json, "{ \"instances\": [ { \"id\": \"a\", \"baseUrl\": \"a.example\" } ] }");
        new ConfigFile(json).addInstance(List.of("instances"), instance("b"));
        assertThat(new ConfigFile(json).removeInstance(List.of("instances"), "a")).isTrue();
        assertThat(new ObjectMapper().readTree(Files.readString(json)).path("instances").findValuesAsText("id")).containsExactly("b");
    }

    @Test
    void envValuesAreOwnerOnly() throws IOException {
        Path env = dir.resolve(".env");
        ConfigFile.appendEnv(env, Map.of("A_PASSWORD", "p w\"x"));
        assertThat(Files.readString(env)).isEqualTo("A_PASSWORD=\"p w\\\"x\"\n");
        ConfigFile.appendEnv(env, Map.of("B_PATH", "C:\\dir\\x"));
        assertThat(ConfigLoader.dotEnv(env, new java.util.ArrayList<>()))
                .containsEntry("A_PASSWORD", "p w\"x").containsEntry("B_PATH", "C:\\dir\\x");
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(env))).isEqualTo("rw-------");
        }
    }
}
