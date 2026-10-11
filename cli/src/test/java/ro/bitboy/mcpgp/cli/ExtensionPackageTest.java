package ro.bitboy.mcpgp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** A definition with an extension, built and tested with the CLI (spec/extensions.md). */
class ExtensionPackageTest {

    static final Path ORDERS_SPEC = Path.of("..", "examples", "platform", "orders", "contracts", "orders-2.0.yaml").toAbsolutePath().normalize();
    static final String TOOL = """
            package com.example.ext;
            import com.fasterxml.jackson.databind.node.JsonNodeFactory;
            import ro.bitboy.mcpgp.extension.*;
            public class Hello implements CustomTool {
                public ToolResult call(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode arguments) {
                    return ToolResult.of(JsonNodeFactory.instance.objectNode().put("greeting", "hello " + arguments.path("who").asText("world")));
                }
            }
            """;

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();

    int run(String... args) {
        return Main.run(new PrintWriter(out, true), new PrintWriter(err, true), args);
    }

    static void jar(Path dir, Path jar) throws Exception {
        Path src = dir.resolve("src/com/example/ext/Hello.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, TOOL);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = ToolProvider.getSystemJavaCompiler().run(null, OutputStream.nullOutputStream(), errors,
                "-d", classes.toString(), "-classpath", System.getProperty("java.class.path"), "--release", "21", src.toString());
        assertThat(code).as(errors.toString()).isZero();
        Files.createDirectories(jar.getParent());
        try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(jar))) {
            o.putNextEntry(new JarEntry("com/example/ext/Hello.class"));
            o.write(Files.readAllBytes(classes.resolve("com/example/ext/Hello.class")));
            o.closeEntry();
        }
    }

    @Test
    void theJarShipsInThePackageAndTheCustomToolRuns(@TempDir Path dir) throws Exception {
        Path project = dir.resolve("orders");
        Files.createDirectories(project.resolve("contracts"));
        Files.copy(ORDERS_SPEC, project.resolve("contracts/orders-2.0.yaml"));
        jar(dir.resolve("build"), project.resolve("ext/hello.jar"));
        Path definition = project.resolve("orders.mcpgp.yaml");
        Files.writeString(definition, Scaffold.definition("orders-mcp", "Orders", "orders", "2.0", "contracts/orders-2.0.yaml") + """
                extensions:
                  artifacts: [ { path: ext/hello.jar } ]
                  customTools:
                    - name: hello
                      description: Says hello.
                      inputSchema: { type: object, properties: { who: { type: string } } }
                      handler: com.example.ext.Hello
                """);
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("tests/hello.yaml"), """
                cases:
                  - name: the custom tool answers
                    tool: orders_hello
                    args: { who: ada }
                    expect: { ok: true, structured: { greeting: hello ada } }
                """);

        Main.env = Map.of("SOURCE_DATE_EPOCH", "1791542400");
        try {
            assertThat(run("build", "--out", dir.resolve("dist").toString(), definition.toString())).as(out + "\n" + err).isZero();
        } finally {
            Main.env = System.getenv();
        }
        Path zip = dir.resolve("dist/orders-mcp-2.0.0.zip");
        try (ZipFile pkg = new ZipFile(zip.toFile())) {
            assertThat(new String(pkg.getInputStream(pkg.getEntry("docs/OPERATIONS.md")).readAllBytes(), StandardCharsets.UTF_8))
                    .contains("## Custom code").contains("`ext/hello.jar`").contains("Custom tool `orders_hello`: `com.example.ext.Hello`");
            Path serverJar = dir.resolve("server.jar");
            Files.copy(pkg.getInputStream(pkg.getEntry("orders-mcp.jar")), serverJar);
            try (ZipFile server = new ZipFile(serverJar.toFile())) {
                assertThat(server.getEntry("BOOT-INF/classes/mcpgp/artifacts/ext/hello.jar")).isNotNull();
            }
        }

        out.getBuffer().setLength(0);
        assertThat(run("test", definition.toString())).as(out + "\n" + err).isZero();
        assertThat(out.toString()).contains("✓ hello.yaml: the custom tool answers");
    }
}
