package ro.bitboy.mcpgp.runtime.mcp;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/** Builds an extension jar from Java sources, against the extension API on the test classpath (what a builder does). */
public final class ExtensionJar {

    private ExtensionJar() {
    }

    /** @param sources class name → source */
    public static Path build(Path dir, Path jar, Map<String, String> sources) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path classes = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-d", classes.toString(), "-classpath", System.getProperty("java.class.path"), "--release", "21"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        if (javac.run(null, OutputStream.nullOutputStream(), errors, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("extension sources don't compile: " + errors);
        }
        Files.createDirectories(jar.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(f));
                out.closeEntry();
            }
        }
        return jar;
    }

    /** A custom tool, an enricher and a call filter, as the tests use them. */
    public static final Map<String, String> SAMPLE = Map.of(
            "com.example.ext.AccountCount", """
                    package com.example.ext;
                    import com.fasterxml.jackson.databind.node.JsonNodeFactory;
                    import ro.bitboy.mcpgp.extension.*;
                    public class AccountCount implements CustomTool {
                        public ToolResult call(ToolContext ctx, com.fasterxml.jackson.databind.JsonNode arguments) {
                            ToolResult accounts = ctx.api().call("orders_account_list", JsonNodeFactory.instance.objectNode());
                            if (accounts.isError()) {
                                return accounts;
                            }
                            ctx.log().info("counted accounts for " + ctx.caller().orElse("someone"));
                            return ToolResult.of(JsonNodeFactory.instance.objectNode().put("accounts", accounts.structured() == null ? -1
                                    : accounts.structured().size()).put("tool", ctx.tool()));
                        }
                    }
                    """,
            "com.example.ext.TenantHeader", """
                    package com.example.ext;
                    import ro.bitboy.mcpgp.extension.*;
                    public class TenantHeader implements RequestEnricher {
                        public void enrich(ToolContext ctx, OutgoingRequest request) {
                            request.setHeader("X-Tenant", "acme-" + ctx.instance().orElse("none"));
                            request.addQuery("source", "mcp");
                            try {
                                request.setHeader("Authorization", "Bearer stolen");
                            } catch (IllegalArgumentException expected) {
                                request.setHeader("X-Auth-Refused", "yes");
                            }
                        }
                    }
                    """,
            "com.example.ext.RefundLimit", """
                    package com.example.ext;
                    import com.fasterxml.jackson.databind.JsonNode;
                    import com.fasterxml.jackson.databind.node.ObjectNode;
                    import ro.bitboy.mcpgp.extension.*;
                    public class RefundLimit implements CallFilter {
                        public ToolResult filter(ToolContext ctx, JsonNode arguments, Chain chain) throws Exception {
                            if (arguments.at("/body/amount").asDouble() > 100) {
                                return ToolResult.error("REFUND_TOO_LARGE", "Refunds over 100 need a person");
                            }
                            ToolResult result = chain.proceed(arguments);
                            if (result.isError()) {
                                return result;
                            }
                            ObjectNode out = ((ObjectNode) result.structured()).deepCopy();
                            out.put("checkedBy", "RefundLimit");
                            return ToolResult.of(out);
                        }
                    }
                    """);
}
