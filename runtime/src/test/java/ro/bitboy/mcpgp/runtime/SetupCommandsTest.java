package ro.bitboy.mcpgp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.runtime.call.MockTarget;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** init, instance add/remove and client add (runtime-config §8, packaging.md §5). */
class SetupCommandsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final String ORDERS = REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;
    MockTarget target;
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        target = new MockTarget();
    }

    @AfterEach
    void stop() {
        target.close();
    }

    int run(String input, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--manifest", ORDERS, "--storage.root", dir.resolve("data").toString()));
        ServerCommands commands = new ServerCommands(Map.of(), dir, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8));
        commands.jar = Path.of("/opt/orders-mcp/orders-mcp.jar");
        return commands.run(all.toArray(String[]::new));
    }

    String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    Path config() {
        return dir.resolve("orders-mcp.yaml");
    }

    /** transport, then one basic-auth instance prod-eu, then no more. */
    String initAnswers() {
        return String.join("\n", "", "prod-eu", target.address(), "", "basic", "svc", "s3cret pass", "n") + "\n";
    }

    @Test
    void initWritesReferencesAndEnvAndTestsTheInstance() throws IOException {
        assertThat(run(initAnswers(), "init")).as(out()).isZero();
        String yaml = Files.readString(config());
        assertThat(yaml).startsWith("# orders-mcp configuration").contains("${env:PROD_EU_PASSWORD}").doesNotContain("s3cret");
        JsonNode parsed = YAML.readTree(yaml);
        assertThat(parsed.at("/instances/0/id").asText()).isEqualTo("prod-eu");
        assertThat(parsed.at("/instances/0/auth/username").asText()).isEqualTo("svc");
        assertThat(Files.readString(dir.resolve(".env"))).isEqualTo("PROD_EU_PASSWORD=\"s3cret pass\"\n");
        assertThat(out()).contains("✓ wrote").contains("✓ instance prod-eu");

        output.reset();
        assertThat(run("", "init")).isEqualTo(1);
        assertThat(out()).contains("already exists");
    }

    @Test
    void initForHttpCreatesAClientKey() throws IOException {
        String answers = String.join("\n", "http", "", "9123", "static", "team-a",
                "prod-eu", target.address(), "", "none", "n") + "\n";
        assertThat(run(answers, "init")).as(out()).isZero();
        JsonNode parsed = YAML.readTree(Files.readString(config()));
        assertThat(parsed.at("/server/port").asInt()).isEqualTo(9123);
        assertThat(parsed.at("/server/auth/keys/0/value").asText()).isEqualTo("${env:KEY_TEAM_A}");
        String env = Files.readString(dir.resolve(".env"));
        assertThat(env).startsWith("KEY_TEAM_A=mcp_");
        assertThat(out()).contains("Client key \"team-a\" (shown once").contains(env.substring("KEY_TEAM_A=".length()).strip());
    }

    @Test
    void instancesCanBeAddedAndRemoved() throws IOException {
        assertThat(run(initAnswers(), "init")).isZero();
        output.reset();
        String add = String.join("\n", target.address(), "1.0", "apiKey", "k-123456") + "\n";
        assertThat(run(add, "instance", "add", "partner-x", "--config", config().toString())).as(out()).isZero();
        JsonNode parsed = YAML.readTree(Files.readString(config()));
        assertThat(parsed.path("instances").findValuesAsText("id")).containsExactly("prod-eu", "partner-x");
        assertThat(parsed.at("/instances/1/contract").asText()).isEqualTo("1.0");
        assertThat(parsed.at("/instances/1/auth/value").asText()).isEqualTo("${env:PARTNER_X_API_KEY}");
        assertThat(Files.readString(dir.resolve(".env"))).contains("PARTNER_X_API_KEY=k-123456");
        assertThat(Files.readString(config())).startsWith("# orders-mcp configuration");     // comments stay

        output.reset();
        assertThat(run(add, "instance", "add", "partner-x", "--config", config().toString())).isEqualTo(1);
        assertThat(out()).contains("already exists");

        output.reset();
        assertThat(run("", "instance", "remove", "partner-x", "--config", config().toString())).isZero();
        assertThat(YAML.readTree(Files.readString(config())).path("instances").findValuesAsText("id")).containsExactly("prod-eu");

        // the last instance can't go: the config wouldn't load
        output.reset();
        String before = Files.readString(config());
        assertThat(run("", "instance", "remove", "prod-eu", "--config", config().toString())).isEqualTo(1);
        assertThat(out()).contains("nothing was changed");
        assertThat(Files.readString(config())).isEqualTo(before);
    }

    @Test
    void clientAddMergesOnlyThisServersEntry() throws IOException {
        assertThat(run(initAnswers(), "init")).isZero();
        Path vscode = dir.resolve("vscode/mcp.json");
        Files.createDirectories(vscode.getParent());
        Files.writeString(vscode, "{ \"servers\": { \"other\": { \"type\": \"http\", \"url\": \"https://other.example/mcp\" } } }");

        output.reset();
        assertThat(run("", "client", "add", "vscode", "--file", vscode.toString(), "--dry-run", "--config", config().toString())).isZero();
        assertThat(out()).contains("(dry run: nothing written)");
        assertThat(JSON.readTree(vscode.toFile()).path("servers").has("orders")).isFalse();

        assertThat(run("", "client", "add", "vscode", "--file", vscode.toString(), "--config", config().toString())).isZero();
        JsonNode written = JSON.readTree(vscode.toFile());
        assertThat(written.at("/servers/other/url").asText()).isEqualTo("https://other.example/mcp");
        JsonNode entry = written.at("/servers/orders");
        assertThat(entry.path("type").asText()).isEqualTo("stdio");
        assertThat(JSON.convertValue(entry.path("args"), List.class)).containsExactly("-jar", "/opt/orders-mcp/orders-mcp.jar",
                "--config", config().toAbsolutePath().toString());
        assertThat(written.toString()).doesNotContain("s3cret");
        assertThat(Files.exists(dir.resolve("vscode/mcp.json.bak"))).isTrue();

        output.reset();
        assertThat(run("", "client", "add", "cursor", "--print", "--config", config().toString())).isZero();
        assertThat(out()).contains("\"orders\"").contains("\"command\"");
    }

    @Test
    void theRunningJarIsFoundWhereverTheLauncherPutIt() throws IOException {
        Path jar = Files.writeString(dir.resolve("orders mcp.jar"), "x");
        // java -jar x.jar: the class path is the jar
        assertThat(ClientRegistration.locateJar(jar.toString(), "jar:nested:/gone/elsewhere.jar/!BOOT-INF/classes/!/")).isEqualTo(jar);
        // a Spring Boot jar seen through its code source, whatever the class path says
        assertThat(ClientRegistration.locateJar("/", "jar:nested:" + dir + "/orders%20mcp.jar/!BOOT-INF/classes/!/")).isEqualTo(jar);
        assertThat(ClientRegistration.locateJar(null, "jar:file:" + dir + "/orders%20mcp.jar!/BOOT-INF/classes!/")).isEqualTo(jar);
        // from an IDE or a classes folder there is no jar
        assertThat(ClientRegistration.locateJar("/work/classes", "file:/work/classes/")).isNull();
    }

    @Test
    void printWorksWithoutAJarAndOtherModesSayWhy() throws IOException {
        Files.writeString(config(), "instances: [ { id: a, baseUrl: \"" + target.address() + "\" } ]\n");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
        ServerCommands commands = new ServerCommands(Map.of(), dir, new ByteArrayInputStream(new byte[0]), stream);
        // no jar override: the test runs from classes, so the jar can't be located
        assertThat(commands.run(new String[] {"client", "add", "cursor", "--print", "--config", config().toString(),
                "--manifest", ORDERS, "--storage.root", dir.resolve("data").toString()})).isZero();
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("couldn't be located").contains("<path to the server jar>");

        captured.reset();
        assertThat(commands.run(new String[] {"client", "add", "cursor", "--file", dir.resolve("c.json").toString(),
                "--config", config().toString(), "--manifest", ORDERS, "--storage.root", dir.resolve("data").toString()})).isEqualTo(1);
        assertThat(captured.toString(StandardCharsets.UTF_8)).contains("isn't running from a jar").contains("--print");
    }

    @Test
    void httpServersRegisterByUrl() throws IOException {
        Files.writeString(config(), "server: { transport: http, publicUrl: \"https://orders-mcp.acme.example\", auth: { mode: none, allowInsecure: true } }\n"
                + "instances: [ { id: a, baseUrl: \"" + target.address() + "\" } ]\n");
        Path cursor = dir.resolve("cursor.json");
        assertThat(run("", "client", "add", "cursor", "--file", cursor.toString(), "--config", config().toString())).as(out()).isZero();
        assertThat(JSON.readTree(cursor.toFile()).at("/mcpServers/orders/url").asText()).isEqualTo("https://orders-mcp.acme.example/mcp");

        output.reset();
        assertThat(run("", "client", "add", "claude-desktop", "--file", dir.resolve("d.json").toString(), "--config", config().toString()))
                .isEqualTo(1);
        assertThat(out()).contains("connector settings").contains("https://orders-mcp.acme.example/mcp");
    }
}
