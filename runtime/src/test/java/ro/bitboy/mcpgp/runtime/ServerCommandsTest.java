package ro.bitboy.mcpgp.runtime;

import org.junit.jupiter.api.AfterEach;
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

/** doctor and instance commands (runtime-config §8). */
class ServerCommandsTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    static final String MANIFEST = REPO.resolve("examples/manifest/orders-mcp.manifest.json").toString();
    static final TestCerts CERTS = TestCerts.get();

    @TempDir
    Path dir;
    MockTarget target;
    MockTarget https;
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    @AfterEach
    void stop() {
        if (target != null) {
            target.close();
        }
        if (https != null) {
            https.close();
        }
    }

    int run(String input, String config, String... args) throws IOException {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, config);
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--manifest", MANIFEST, "--config", file.toString(), "--storage.root", dir.resolve("data").toString()));
        return new ServerCommands(Map.of(), dir, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8)).run(all.toArray(String[]::new));
    }

    String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void doctorChecksEveryInstance() throws IOException {
        target = new MockTarget();
        int code = run("", """
                instances:
                  - { id: ok, baseUrl: "%s" }
                  - { id: down, baseUrl: "http://127.0.0.1:1" }
                """.formatted(target.address()), "doctor");
        assertThat(code).isEqualTo(1);
        assertThat(out()).contains("✓ config").contains("✓ instance ok").contains("✗ instance down").contains("tcp: failed")
                .contains("Some checks failed.");
    }

    @Test
    void doctorSaysWhichVersionEachTargetRunsAndWarnsAboutAWrongPin() throws IOException {
        target = new MockTarget();
        target.respondToVersion(MockTarget.Response.json(200, "{\"version\":\"2.0.4\"}"))
                .respondToVersion(MockTarget.Response.json(200, "{\"version\":\"2.0.4\"}"));
        run("", """
                instances:
                  - { id: auto, baseUrl: "%1$s" }
                  - { id: pinned, baseUrl: "%1$s", contract: "1.0" }
                """.formatted(target.address()), "doctor");
        assertThat(out()).contains("contract: 2.0, detected (the target reports 2.0.4)")
                .contains("! instance pinned is pinned to contract 1.0, but its target runs 2.0");
    }

    @Test
    void doctorReportsConfigProblemsAndInlineSecrets() throws IOException {
        assertThat(run("", "instances: [ { id: a, baseUrl: \"ftp://x\" } ]\n", "doctor")).isEqualTo(1);
        assertThat(out()).contains("✗ config").contains("/instances/0/baseUrl: only http and https");

        output.reset();
        target = new MockTarget();
        assertThat(run("", "allowInlineSecrets: true\ninstances: [ { id: a, baseUrl: \"%s\", auth: { type: apiKey, value: plain-key } } ]\n"
                .formatted(target.address()), "doctor")).isEqualTo(1);
        assertThat(out()).contains("✗ allowInlineSecrets is on").doesNotContain("plain-key");
    }

    @Test
    void quickDoctorAsksTheRunningServer() throws IOException {
        assertThat(run("", "server: { transport: http, port: 1 }\ninstances: [ { id: a, baseUrl: a.example } ]\n", "doctor", "--quick"))
                .isEqualTo(1);
        assertThat(out()).contains("✗ server does not answer at http://127.0.0.1:1/health/live");
    }

    @Test
    void listsInstances() throws IOException {
        assertThat(run("", "instances:\n  - { id: prod-eu, baseUrl: eu.example, contract: \"1.0\" }\n", "instance", "list")).isZero();
        assertThat(out()).contains("prod-eu").contains("orders").contains("1.0").contains("eu.example");
    }

    @Test
    void trustSavesTheCertificateOnlyAfterConfirmation() throws Exception {
        https = MockTarget.https(CERTS.serverP12, null);
        String config = "instances:\n  - { id: secure, baseUrl: \"" + https.address() + "\" }\n";
        assertThat(run("n\n", config, "instance", "trust", "secure")).isEqualTo(1);
        assertThat(out()).contains("subject  CN=localhost").contains("sha256").contains("Nothing saved.");
        assertThat(dir.resolve("certs/secure.pem")).doesNotExist();

        output.reset();
        assertThat(run("", config, "instance", "trust", "secure", "--yes")).isZero();
        assertThat(dir.resolve("certs/secure.pem")).exists();
        assertThat(out()).contains("✓ instance secure").contains("tls: trusted").contains("ca: { path: ./certs/secure.pem }");

        // configured material is never replaced
        output.reset();
        assertThat(run("", "instances:\n  - { id: secure, baseUrl: \"" + https.address() + "\", tls: { ca: { path: ./certs/secure.pem } } }\n",
                "instance", "trust", "secure", "--yes")).isEqualTo(1);
        assertThat(out()).contains("already has tls.ca");
    }

    @Test
    void usageAndUnknownInstances() throws IOException {
        assertThat(run("", "instances: [ { id: a, baseUrl: a.example } ]\n", "instance", "frobnicate")).isEqualTo(2);
        assertThat(run("", "instances: [ { id: a, baseUrl: a.example } ]\n", "instance", "test", "zzz")).isEqualTo(1);
        assertThat(out()).contains("No instance named 'zzz' (configured: a)");
    }
}
