package ro.bitboy.mcpgp.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The server as a real process: stdout carries only JSON-RPC, logs go to stderr, stdin EOF ends it. */
class StdioProcessTest {

    static final Path REPO = Path.of("..").toAbsolutePath().normalize();

    @Test
    void stdoutCarriesOnlyTheProtocolAndTheProcessEndsWithStdin(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("server.yaml");
        Files.writeString(config, "instance: { baseUrl: orders.example }\n");
        Path out = dir.resolve("out.txt");
        Path err = dir.resolve("err.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), McpgpServer.class.getName(),
                "--manifest", REPO.resolve("conformance/cases/content-types/multipart-split/expected/manifest.json").toString(),
                "--config", config.toString(), "--storage.root", dir.resolve("data").toString())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                    """).getBytes(StandardCharsets.UTF_8));
            in.flush();
        }   // closed at once: the server must still answer what it has read before it exits
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("process ends when stdin closes").isTrue();
        assertThat(process.exitValue()).isZero();
        List<String> stdout = Files.readAllLines(out);
        assertThat(stdout).hasSize(2).allMatch(line -> line.startsWith("{\"jsonrpc\":\"2.0\""));
        assertThat(Files.readAllLines(err)).isNotEmpty().allMatch(line -> line.startsWith("{\"ts\":"));
    }
}
