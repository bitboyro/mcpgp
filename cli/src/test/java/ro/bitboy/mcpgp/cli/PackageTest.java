package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Error;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.packager.Minisign;
import ro.bitboy.mcpgp.packager.Packager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** `mcpgp build`: the package's contents, its reproducibility, and that the packaged jar runs. */
class PackageTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    static final String WORKSPACE = EXAMPLES.resolve("workspace").toString();
    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @TempDir
    static Path dist;
    static Path zip;
    static String stderr;
    static String ordersWarnings;

    static int build(Path out, String definition) {
        StringWriter err = new StringWriter();
        int code = Main.run(new PrintWriter(new StringWriter(), true), new PrintWriter(err, true),
                "build", "--workspace", WORKSPACE, "--build-number", "3", "--out", out.toString(), definition);
        stderr = err.toString();
        return code;
    }

    static Minisign.Keys keys;

    @BeforeAll
    static void buildOrders() throws Exception {
        keys = Minisign.generate();
        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400", "MCPGP_SIGNING_KEY", Minisign.secretKeyFile(keys));
        assertThat(build(dist, EXAMPLES.resolve("orders/orders.mcpgp.yaml").toString())).as(stderr).isZero();
        zip = dist.resolve("orders-mcp-2.0.0+3.zip");
        ordersWarnings = stderr;
    }

    static byte[] entry(Path zipFile, String name) throws IOException {
        try (ZipFile file = new ZipFile(zipFile.toFile())) {
            return file.getInputStream(file.getEntry(name)).readAllBytes();
        }
    }

    @Test
    void containsTheJarManifestSchemaExampleAndChecksums() throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Collections.list(file.entries()).forEach(e -> names.add(e.getName()));
        }
        assertThat(names).containsExactly("AGENTS.md", "SHA256SUMS", "SHA256SUMS.sig", "SIGNING-KEY.pub", "docker/Dockerfile",
                "docker/docker-compose.yml", "docs/CONFIGURATION.md", "docs/INSTALL.md", "docs/OPERATIONS.md", "docs/README.md", "docs/TOOLS.md",
                "llms.txt", "manifest.json", "orders-mcp-2.0.0+3.mcpb", "orders-mcp.example.yaml", "orders-mcp.jar", "server.config.schema.json");
        String sums = new String(entry(zip, "SHA256SUMS"), StandardCharsets.UTF_8);
        for (String name : names) {
            if (!name.startsWith("SHA256SUMS")) {
                assertThat(sums).contains(Packager.sha256(entry(zip, name)) + "  " + name + "\n");
            }
        }
        assertThat(ordersWarnings).doesNotContain("build target mcpb").doesNotContain("build target docker");
        assertThat(dist.resolve("orders-mcp-2.0.0+3.manifest.json")).exists();
    }

    @Test
    void theJarEmbedsTheManifestAndKeepsNestedJarsStored(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("orders-mcp.jar");
        Files.write(jar, entry(zip, "orders-mcp.jar"));
        try (ZipFile file = new ZipFile(jar.toFile())) {
            assertThat(file.getInputStream(file.getEntry(Packager.MANIFEST_ENTRY)).readAllBytes()).isEqualTo(entry(zip, "manifest.json"));
            List<? extends ZipEntry> libs = Collections.list(file.entries()).stream().filter(e -> e.getName().startsWith("BOOT-INF/lib/") && !e.isDirectory()).toList();
            assertThat(libs).isNotEmpty().allMatch(e -> e.getMethod() == ZipEntry.STORED);
        }
    }

    @Test
    void isReproducible(@TempDir Path again) throws IOException {
        assertThat(build(again, EXAMPLES.resolve("orders/orders.mcpgp.yaml").toString())).isZero();
        assertThat(Files.readAllBytes(again.resolve("orders-mcp-2.0.0+3.zip"))).isEqualTo(Files.readAllBytes(zip));
    }

    @Test
    void theExampleConfigMatchesTheBuildsSchema() throws IOException {
        JsonNode schema = JSON.readTree(entry(zip, "server.config.schema.json"));
        JsonNode example = YAML.readTree(entry(zip, "orders-mcp.example.yaml"));
        List<Error> errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema).validate(example);
        assertThat(errors).isEmpty();
        assertThat(schema.at("/$defs/instance_orders/properties/contract/enum")).extracting(JsonNode::asText).containsExactly("auto", "1.0", "2.0");
        assertThat(schema.at("/$defs/auth_orders/oneOf").findValuesAsText("const"))
                .containsExactlyInAnyOrder("basic", "apiKey", "oauth2ClientCredentials", "none");
        assertThat(schema.at("/properties").has("instance")).isFalse();
        // a sign-in type this build doesn't support is rejected by the schema
        JsonNode bearer = YAML.readTree("instances: [ { id: a, baseUrl: x.example, auth: { type: bearer, token: \"${env:T}\" } } ]");
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema).validate(bearer)).isNotEmpty();
    }

    @Test
    void singleInstanceStdioBuildsHaveTheirOwnShape(@TempDir Path out) throws IOException {
        assertThat(build(out, EXAMPLES.resolve("orders-readonly/orders-readonly.mcpgp.yaml").toString())).as(stderr).isZero();
        Path readonly;
        try (var files = Files.list(out)) {
            readonly = files.filter(p -> p.toString().endsWith(".zip")).findFirst().orElseThrow();
        }
        JsonNode schema = JSON.readTree(entry(readonly, "server.config.schema.json"));
        assertThat(schema.at("/properties").has("instance")).isTrue();
        assertThat(schema.at("/properties").has("instances")).isFalse();
        assertThat(schema.at("/properties").has("server")).isFalse();      // stdio only
        JsonNode example = YAML.readTree(entry(readonly, "orders-readonly.example.yaml"));
        assertThat(SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema).validate(example)).isEmpty();
    }

    @Test
    void thePackagedJarRuns(@TempDir Path tmp) throws Exception {
        Path jar = tmp.resolve("orders-mcp.jar");
        Files.write(jar, entry(zip, "orders-mcp.jar"));
        Path config = tmp.resolve("orders-mcp.yaml");
        Files.writeString(config, "instances: [ { id: prod, baseUrl: orders.example } ]\n");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString(),
                "--config", config.toString(), "--storage.root", tmp.resolve("data").toString())
                .redirectError(tmp.resolve("err.txt").toFile()).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                    """).getBytes(StandardCharsets.UTF_8));
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        List<String> lines = stdout.lines().toList();
        assertThat(lines).as(Files.readString(tmp.resolve("err.txt"))).hasSize(2);
        assertThat(JSON.readTree(lines.get(0)).at("/result/serverInfo/version").asText()).isEqualTo("2.0.0+3");
        assertThat(JSON.readTree(lines.get(1)).at("/result/tools").findValuesAsText("name")).contains("orders_customer_get");
    }

    @Test
    void theSignatureAndChecksumsVerify(@TempDir Path tmp) throws Exception {
        Path pub = tmp.resolve("builder.pub");
        Files.writeString(pub, Minisign.publicKeyFile(keys));
        StringWriter out = new StringWriter();
        assertThat(Main.run(new PrintWriter(out, true), new PrintWriter(new StringWriter(), true), "verify", zip.toString(), "--key", pub.toString()))
                .as(out.toString()).isZero();
        assertThat(out.toString()).contains("✓ signature (timestamp:1791542400 file:SHA256SUMS hashed server:orders-mcp-2.0.0+3)")
                .contains("✓ all 15 files match SHA256SUMS");
        assertThat(new String(entry(zip, "SIGNING-KEY.pub"), StandardCharsets.UTF_8)).isEqualTo(Minisign.publicKeyFile(keys));

        // a changed file is caught
        Path changed = tmp.resolve("changed.zip");
        try (ZipFile in = new ZipFile(zip.toFile());
             java.util.zip.ZipOutputStream copy = new java.util.zip.ZipOutputStream(Files.newOutputStream(changed))) {
            for (ZipEntry e : Collections.list(in.entries())) {
                copy.putNextEntry(new ZipEntry(e.getName()));
                byte[] data = in.getInputStream(e).readAllBytes();
                copy.write(e.getName().equals("orders-mcp.example.yaml") ? "instances: []\n".getBytes(StandardCharsets.UTF_8) : data);
                copy.closeEntry();
            }
        }
        StringWriter changedOut = new StringWriter();
        assertThat(Main.run(new PrintWriter(changedOut, true), new PrintWriter(new StringWriter(), true), "verify", changed.toString(),
                "--key", pub.toString())).isEqualTo(1);
        assertThat(changedOut.toString()).contains("✗ orders-mcp.example.yaml: checksum differs").contains("the package was changed");
    }

    @Test
    void docsMatchTheBuild() throws IOException {
        String tools = new String(entry(zip, "docs/TOOLS.md"), StandardCharsets.UTF_8);
        assertThat(tools).contains("| `orders_customer_contact_list` |").contains("| 2.0 |").contains("Every tool (except `instances_list`) takes `instance`");
        String configuration = new String(entry(zip, "docs/CONFIGURATION.md"), StandardCharsets.UTF_8);
        assertThat(configuration).contains("`instances[].baseUrl`").contains("`ORDERS_MCP_INSTANCES_<n>__BASEURL`")
                .contains("`instances[].auth.password`").contains("**secret**")
                .contains("| `instances[].auth.type` | `basic` · `apiKey` · `oauth2ClientCredentials` · `none` |")
                .doesNotContain("keychain");
        String install = new String(entry(zip, "docs/INSTALL.md"), StandardCharsets.UTF_8);
        assertThat(install).contains("minisign -V -p SIGNING-KEY.pub -m SHA256SUMS -x SHA256SUMS.sig");
        assertThat(new String(entry(zip, "AGENTS.md"), StandardCharsets.UTF_8)).contains("Call `instances_list` first");
    }

    @Test
    void aSigningKeyMustBeAReference(@TempDir Path tmp) {
        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400");
        try {
            assertThat(build(tmp, EXAMPLES.resolve("orders/orders.mcpgp.yaml").toString())).isEqualTo(1);
            assertThat(stderr).contains("build.sign.key: environment variable MCPGP_SIGNING_KEY is not set");
        } finally {
            Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400", "MCPGP_SIGNING_KEY", Minisign.secretKeyFile(keys));
        }
    }
}
