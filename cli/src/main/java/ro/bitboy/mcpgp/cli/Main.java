package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ro.bitboy.mcpgp.cli.pack.Minisign;
import ro.bitboy.mcpgp.cli.pack.Packager;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.core.manifest.ManifestWriter;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** {@code mcpgp}: the command line (spec/cli.md). Exit codes follow cli.md §4. */
@Command(name = "mcpgp", mixinStandardHelpOptions = true, version = Compiler.VERSION,
        description = "Generate MCP servers from OpenAPI contracts.",
        subcommands = {Main.Validate.class, Main.Build.class, Main.Explain.class, Main.Keygen.class, Main.Verify.class})
public final class Main {

    static final int OK = 0;
    static final int ERRORS = 1;
    static final int USAGE = 2;
    static final int NAME_DRIFT = 3;
    static final int INTERNAL = 10;

    /** Environment, replaceable in tests. */
    static Map<String, String> env = System.getenv();

    public static void main(String[] args) {
        System.exit(run(new PrintWriter(System.out, true, StandardCharsets.UTF_8),
                new PrintWriter(System.err, true, StandardCharsets.UTF_8), args));
    }

    static int run(PrintWriter out, PrintWriter err, String... args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setOut(out);
        cli.setErr(err);
        cli.setExecutionExceptionHandler((e, commandLine, parseResult) -> {
            commandLine.getErr().println("internal error: " + e);
            return INTERNAL;
        });
        return cli.execute(args);
    }

    /** Options shared by every command. */
    static class Common {
        @Option(names = "--workspace", paramLabel = "<dir>",
                description = "Workspace directory (with mcpgp-workspace.yaml) for mcpgp:// references.")
        Path workspace;

        @Option(names = "--json", description = "Machine-readable output.")
        boolean json;

        @Option(names = "--quiet", description = "Only print errors.")
        boolean quiet;

        @CommandLine.Spec(CommandLine.Spec.Target.MIXEE)
        CommandLine.Model.CommandSpec spec;

        Schemas schemas = new Schemas();

        Workspace workspace(Diagnostics diagnostics) {
            return workspace == null ? Workspace.none()
                    : Workspace.load(workspace.resolve("mcpgp-workspace.yaml"), schemas, diagnostics);
        }

        PrintWriter out() {
            return spec.commandLine().getOut();
        }

        /** Prints diagnostics and returns the exit code they imply. */
        int report(Path definition, List<Diagnostic> diagnostics) {
            if (json) {
                ArrayNode list = new ObjectMapper().createArrayNode();
                for (Diagnostic d : diagnostics) {
                    ObjectNode node = list.addObject()
                            .put("definition", definition.toString())
                            .put("level", d.level().name().toLowerCase())
                            .put("code", d.code())
                            .put("message", d.message());
                    if (d.location() != null) {
                        ObjectNode location = node.putObject("location").put("file", d.location().file());
                        if (d.location().line() != null) {
                            location.put("line", d.location().line());
                        }
                        if (d.location().pointer() != null) {
                            location.put("pointer", d.location().pointer());
                        }
                    }
                }
                out().println(CanonicalJson.compact(list));
            } else {
                for (Diagnostic d : diagnostics) {
                    if (!quiet || d.level() == Diagnostic.Level.ERROR) {
                        out().println(definition + ": " + d);
                    }
                }
            }
            return exitCode(diagnostics);
        }
    }

    static int exitCode(List<Diagnostic> diagnostics) {
        boolean errors = diagnostics.stream().anyMatch(d -> d.level() == Diagnostic.Level.ERROR);
        if (!errors) {
            return OK;
        }
        boolean drift = diagnostics.stream().anyMatch(d -> d.code().equals(Codes.NAME_DRIFT_FROZEN));
        return drift ? NAME_DRIFT : ERRORS;
    }

    @Command(name = "validate", description = "Schema and compiler checks; writes nothing.")
    static final class Validate implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Option(names = "--frozen-lock", description = "Fail when a tool name would differ from mcp-names.lock.")
        boolean frozenLock;

        @Parameters(arity = "1..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Override
        public Integer call() {
            int code = OK;
            for (Path definition : definitions) {
                Diagnostics workspaceDiagnostics = new Diagnostics();
                Workspace workspace = common.workspace(workspaceDiagnostics);
                Compiler.Result result = new Compiler(common.schemas).compile(definition,
                        new Compiler.Options(workspace, frozenLock));
                List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
                all.addAll(result.diagnostics());
                code = Math.max(code, common.report(definition, all));
                if (!common.json && !common.quiet && result.ok()) {
                    common.out().println(definition + ": ok, " + result.tools().size() + " API tools");
                }
            }
            return code;
        }
    }

    @Command(name = "build", description = "Compile and package a definition.")
    static final class Build implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Parameters(paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Option(names = "--manifest-only", description = "Write only the manifest, no package.")
        boolean manifestOnly;

        @Option(names = "--out", paramLabel = "<dir>", defaultValue = "dist", description = "Output directory (default: ${DEFAULT-VALUE}).")
        Path out;

        @Option(names = "--version", paramLabel = "<v>", description = "Server version; default: the default contract's version.")
        String version;

        @Option(names = "--build-number", paramLabel = "<n>", description = "Added as SemVer build metadata, e.g. 2.0.0+3.")
        Integer buildNumber;

        @Option(names = "--source", paramLabel = "<uri>", description = "Where the definition comes from, e.g. git+https://…#<commit>.")
        String source;

        @Option(names = "--frozen-lock", description = "Fail when a tool name would differ from mcp-names.lock (exit code 3).")
        boolean frozenLock;

        @Override
        public Integer call() throws IOException {
            Diagnostics workspaceDiagnostics = new Diagnostics();
            Workspace workspace = common.workspace(workspaceDiagnostics);
            ManifestWriter.Build build = new ManifestWriter.Build(createdAt(definition), Compiler.VERSION, version, buildNumber, source);
            Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, frozenLock, build));
            List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
            all.addAll(result.diagnostics());
            int code = common.report(definition, all);
            if (code != OK || result.manifest().isEmpty()) {
                return code == OK ? ERRORS : code;
            }
            ObjectNode manifest = result.manifest().get();
            if (manifestOnly) {
                String name = manifest.at("/server/name").asText() + "-" + manifest.at("/server/version").asText();
                Files.createDirectories(out);
                Path file = out.resolve(name + ".manifest.json");
                Files.writeString(file, CanonicalJson.pretty(manifest));
                if (!common.json && !common.quiet) {
                    common.out().println("wrote " + file + " (" + manifest.path("tools").size() + " tools)");
                }
                return OK;
            }
            com.fasterxml.jackson.databind.JsonNode document = result.definition().orElseThrow().document();
            Minisign.Keys signing;
            try {
                signing = signingKey(document.at("/build/sign/key"), result.definition().get().root());
            } catch (IllegalArgumentException e) {
                common.spec.commandLine().getErr().println("error: build.sign.key: " + e.getMessage());
                return ERRORS;
            }
            Packager.Result packaged = new Packager(Packager::runtimeJar)
                    .build(manifest, document, build.createdAt(), out, signing);
            packaged.warnings().forEach(w -> common.spec.commandLine().getErr().println("warning: " + w));
            if (!common.json && !common.quiet) {
                common.out().println("wrote " + packaged.zip() + " (" + manifest.path("tools").size() + " tools) and " + packaged.manifest());
            }
            return OK;
        }
    }

    @Command(name = "explain", description = "Where a tool's name, title and description come from.")
    static final class Explain implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Parameters(index = "0", paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Parameters(index = "1", paramLabel = "<tool>", description = "Tool name, as in the manifest.")
        String tool;

        @Override
        public Integer call() {
            Diagnostics workspaceDiagnostics = new Diagnostics();
            Workspace workspace = common.workspace(workspaceDiagnostics);
            Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, false));
            List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
            all.addAll(result.diagnostics());
            if (result.manifest().isEmpty()) {
                int code = common.report(definition, all);
                return code == OK ? ERRORS : code;
            }
            JsonNode found = null;
            List<String> names = new ArrayList<>();
            for (JsonNode t : result.manifest().get().path("tools")) {
                names.add(t.path("name").asText());
                if (t.path("name").asText().equals(tool)) {
                    found = t;
                }
            }
            if (found == null) {
                common.spec.commandLine().getErr().println("No tool named " + tool + " in " + definition
                        + ". Tools: " + String.join(", ", names));
                return ERRORS;
            }
            if (common.json) {
                ObjectNode explained = new ObjectMapper().createObjectNode().put("name", tool);
                explained.set("operation", found.path("operation"));
                explained.set("provenance", found.path("provenance"));
                common.out().println(CanonicalJson.compact(explained));
                return OK;
            }
            PrintWriter out = common.out();
            out.println(tool + (found.has("operation") ? "  (" + found.at("/operation/key").asText() + ")" : "  (" + found.path("kind").asText() + ")"));
            for (String field : List.of("name", "title", "description")) {
                JsonNode steps = found.path("provenance").path(field);
                if (steps.isEmpty()) {
                    continue;
                }
                out.println(field + ":");
                for (JsonNode step : steps) {
                    StringBuilder line = new StringBuilder("  " + String.format("%-18s", step.path("step").asText()));
                    if (step.has("value")) {
                        line.append(" ").append(step.path("value").asText());
                    }
                    List<String> from = new ArrayList<>();
                    if (step.has("file")) {
                        from.add(step.path("file").asText() + (step.has("pointer") ? "#" + step.path("pointer").asText() : ""));
                    }
                    if (step.has("op")) {
                        from.add(step.path("op").asText());
                    }
                    if (step.has("since")) {
                        from.add("since " + step.path("since").asText());
                    }
                    if (!from.isEmpty()) {
                        line.append("   [").append(String.join(", ", from)).append("]");
                    }
                    out.println(line);
                }
            }
            return OK;
        }
    }

    /**
     * The builder's signing key from {@code build.sign.key}: a secret reference ({@code ${env:NAME}} or
     * {@code ${file:path}}, relative to the definition), never a key written in the definition.
     */
    static Minisign.Keys signingKey(com.fasterxml.jackson.databind.JsonNode reference, Path root) {
        if (reference.isMissingNode() || reference.isNull()) {
            return null;
        }
        java.util.regex.Matcher ref = java.util.regex.Pattern.compile("^\\$\\{(env|file):([^}]+)}$").matcher(reference.asText());
        if (!ref.matches()) {
            throw new IllegalArgumentException("must be a reference like ${env:MCPGP_SIGNING_KEY} or ${file:path}, never the key itself");
        }
        String text;
        if (ref.group(1).equals("env")) {
            text = env.get(ref.group(2));
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("environment variable " + ref.group(2) + " is not set");
            }
        } else {
            try {
                text = Files.readString(root.resolve(ref.group(2)));
            } catch (IOException e) {
                throw new IllegalArgumentException("cannot read " + ref.group(2));
            }
        }
        return Minisign.readSecretKey(text);
    }

    @Command(name = "keygen", description = "Create a signing key pair (Ed25519, minisign format).")
    static final class Keygen implements Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = "--out", paramLabel = "<dir>", defaultValue = ".", description = "Where to write mcpgp.key and mcpgp.pub.")
        Path out;

        @Override
        public Integer call() throws Exception {
            Path secret = out.resolve("mcpgp.key");
            Path pub = out.resolve("mcpgp.pub");
            if (Files.exists(secret)) {
                spec.commandLine().getErr().println(secret + " exists; not overwriting it");
                return ERRORS;
            }
            Minisign.Keys keys = Minisign.generate();
            Files.createDirectories(out);
            Files.writeString(secret, Minisign.secretKeyFile(keys));
            try {
                Files.setPosixFilePermissions(secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // not a POSIX file system
            }
            Files.writeString(pub, Minisign.publicKeyFile(keys));
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("wrote " + secret + " (secret: keep it out of the repository) and " + pub + " (publish it)");
            writer.println("key id " + Minisign.keyIdText(keys.keyId()) + "; give builds the key with build.sign.key: \"${env:MCPGP_SIGNING_KEY}\"");
            return OK;
        }
    }

    @Command(name = "verify", description = "Verify a package: the signature of SHA256SUMS and every file's checksum.")
    static final class Verify implements Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Parameters(paramLabel = "<zip>", description = "The package.")
        Path zip;

        @Option(names = "--key", paramLabel = "<pub>", required = true,
                description = "The builder's public key, as published by the builder (not only the copy in the package).")
        Path key;

        @Override
        public Integer call() throws Exception {
            PrintWriter writer = spec.commandLine().getOut();
            java.util.Map<String, byte[]> entries = new java.util.TreeMap<>();
            try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
                for (java.util.zip.ZipEntry entry : java.util.Collections.list(file.entries())) {
                    entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
                }
            }
            byte[] sums = entries.get("SHA256SUMS");
            byte[] sig = entries.get("SHA256SUMS.sig");
            if (sums == null || sig == null) {
                writer.println("✗ the package has no " + (sums == null ? "SHA256SUMS" : "SHA256SUMS.sig"));
                return ERRORS;
            }
            try {
                String trusted = Minisign.verify(Minisign.readPublicKey(Files.readString(key)), sums,
                        new String(sig, java.nio.charset.StandardCharsets.UTF_8));
                writer.println("✓ signature (" + trusted.replace('\t', ' ') + ")");
            } catch (IllegalArgumentException e) {
                writer.println("✗ signature: " + e.getMessage());
                return ERRORS;
            }
            boolean ok = true;
            java.util.Set<String> listed = new java.util.HashSet<>();
            for (String line : new String(sums, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String hex = line.substring(0, 64);
                String name = line.substring(66);
                listed.add(name);
                byte[] data = entries.get(name);
                boolean match = data != null && Packager.sha256(data).equals(hex);
                ok &= match;
                if (!match) {
                    writer.println("✗ " + name + (data == null ? ": missing" : ": checksum differs"));
                }
            }
            for (String name : entries.keySet()) {
                if (!listed.contains(name) && !name.equals("SHA256SUMS") && !name.equals("SHA256SUMS.sig")) {
                    writer.println("✗ " + name + ": not listed in SHA256SUMS");
                    ok = false;
                }
            }
            writer.println(ok ? "✓ all " + listed.size() + " files match SHA256SUMS" : "✗ the package was changed");
            return ok ? OK : ERRORS;
        }
    }

    /** {@code SOURCE_DATE_EPOCH}, else the last commit's time, else now (cli.md §6). */
    static Instant createdAt(Path definition) {
        String epoch = env.get("SOURCE_DATE_EPOCH");
        if (epoch != null && epoch.matches("\\d+")) {
            return Instant.ofEpochSecond(Long.parseLong(epoch));
        }
        try {
            Path dir = definition.toAbsolutePath().getParent();
            Process git = new ProcessBuilder("git", "-C", dir.toString(), "log", "-1", "--format=%ct")
                    .redirectErrorStream(true).start();
            String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (git.waitFor(10, TimeUnit.SECONDS) && git.exitValue() == 0 && output.matches("\\d+")) {
                return Instant.ofEpochSecond(Long.parseLong(output));
            }
        } catch (IOException e) {
            // no git: fall through
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Instant.now();
    }
}
