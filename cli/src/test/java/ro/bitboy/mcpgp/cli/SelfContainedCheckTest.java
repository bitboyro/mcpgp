package ro.bitboy.mcpgp.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import ro.bitboy.mcpgp.cli.pack.Minisign;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The package as a customer gets it (roadmap M4): checksums with {@code sha256sum -c}, the signature
 * with the real {@code minisign -V}, and the self-contained rule (packaging.md §1): the packaged server,
 * run under {@code strace}, connects only to the configured target on loopback, and resolves no
 * host name. Runs in CI ({@code MCPGP_PACKAGE_CHECK=1}, Linux with strace and minisign installed).
 */
@EnabledIfEnvironmentVariable(named = "MCPGP_PACKAGE_CHECK", matches = "1")
class SelfContainedCheckTest {

    static final Path EXAMPLES = Path.of("..", "examples", "platform").toAbsolutePath().normalize();
    private static final Pattern INET = Pattern.compile("connect\\(\\d+, \\{sa_family=AF_INET, sin_port=htons\\((\\d+)\\), sin_addr=inet_addr\\(\"([0-9.]+)\"\\)");
    private static final Pattern INET6 = Pattern.compile("connect\\(\\d+, \\{sa_family=AF_INET6, sin6_port=htons\\((\\d+)\\),.*?inet_pton\\(AF_INET6, \"([0-9a-fA-F:.]+)\"");

    @TempDir
    Path dir;

    static Process run(Path workDir, String... command) throws IOException {
        return new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true).start();
    }

    static String output(Process process) throws Exception {
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return text;
    }

    @Test
    void thePackageVerifiesAndTalksOnlyToTheConfiguredTarget() throws Exception {
        // build a signed package, as CI would with the builder's key
        Minisign.Keys keys = Minisign.generate();
        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400", "MCPGP_SIGNING_KEY", Minisign.secretKeyFile(keys));
        Path dist = dir.resolve("dist");
        StringWriter err = new StringWriter();
        assertThat(Main.run(new PrintWriter(new StringWriter(), true), new PrintWriter(err, true), "build",
                "--workspace", EXAMPLES.resolve("workspace").toString(), "--out", dist.toString(),
                EXAMPLES.resolve("orders/orders.mcpgp.yaml").toString())).as(err.toString()).isZero();
        Path pkg = Files.createDirectories(dir.resolve("pkg"));
        try (ZipFile zip = new ZipFile(dist.resolve("orders-mcp-2.0.0.zip").toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                Path target = pkg.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                Files.write(target, zip.getInputStream(entry).readAllBytes());
            }
        }

        // what the customer runs
        Process sums = run(pkg, "sha256sum", "-c", "SHA256SUMS");
        String sumsOut = output(sums);
        assertThat(sums.exitValue()).as(sumsOut).isZero();
        Process minisign = run(pkg, "minisign", "-V", "-p", "SIGNING-KEY.pub", "-m", "SHA256SUMS", "-x", "SHA256SUMS.sig");
        String minisignOut = output(minisign);
        assertThat(minisign.exitValue()).as(minisignOut).isZero();
        assertThat(minisignOut).contains("Signature and comment signature verified");

        // a target on loopback, by IP (so nothing needs a name lookup)
        AtomicInteger requests = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        target.start();
        int port = target.getAddress().getPort();
        try {
            Path config = pkg.resolve("orders-mcp.yaml");
            Files.writeString(config, "instances: [ { id: prod, baseUrl: \"http://127.0.0.1:" + port + "\" } ]\n");
            Path trace = dir.resolve("trace.txt");
            Process server = new ProcessBuilder("strace", "-f", "-qq", "-e", "trace=connect", "-o", trace.toString(),
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", "orders-mcp.jar",
                    "--config", config.toString(), "--storage.root", dir.resolve("data").toString())
                    .directory(pkg.toFile()).redirectError(dir.resolve("err.txt").toFile()).start();
            try (OutputStream in = server.getOutputStream()) {
                in.write(("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"ci","version":"1"}}}
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orders_customer_get","arguments":{"instance":"prod","path":{"id":"1"}}}}
                        """).getBytes(StandardCharsets.UTF_8));
            }
            String stdout = new String(server.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(server.waitFor(120, TimeUnit.SECONDS)).isTrue();
            assertThat(stdout.lines().count()).as(Files.readString(dir.resolve("err.txt"))).isEqualTo(3);
            assertThat(requests.get()).as("the tool call reached the target").isEqualTo(1);

            List<String> outside = new ArrayList<>();
            for (String line : Files.readAllLines(trace)) {
                for (Pattern pattern : List.of(INET, INET6)) {
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        int connectPort = Integer.parseInt(m.group(1));
                        String address = m.group(2);
                        boolean loopback = address.startsWith("127.") || address.equals("::1");
                        if (!loopback || connectPort == 53 || connectPort != port) {
                            outside.add(address + ":" + connectPort);
                        }
                    }
                }
            }
            assertThat(outside).as("connections other than the configured target").isEmpty();
        } finally {
            target.stop(0);
        }
    }
}
