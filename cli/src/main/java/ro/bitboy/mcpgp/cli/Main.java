package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ro.bitboy.mcpgp.packager.Minisign;
import ro.bitboy.mcpgp.packager.Packager;
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
        subcommands = {Main.Init.class, Main.Validate.class, Main.Build.class, Main.Explain.class, Main.Names.class, Main.Test.class, Main.PushCommand.class, Main.Keygen.class, Main.Verify.class})
public final class Main {

    static final int OK = 0;
    static final int ERRORS = 1;
    static final int USAGE = 2;
    static final int NAME_DRIFT = 3;
    static final int TESTS_FAILED = 4;
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

    @Command(name = "init", description = "Create a definition project, from an OpenAPI or Swagger file when given one.")
    static final class Init implements Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Option(names = "--from-spec", paramLabel = "<file|url>", description = "The API's OpenAPI (3.x) or Swagger (2.0) document.")
        String fromSpec;

        @Option(names = "--name", paramLabel = "<name>", description = "Server name; default: from the spec's title, e.g. orders-mcp.")
        String name;

        @Option(names = "--out", paramLabel = "<dir>", defaultValue = ".", description = "Project directory (default: the current one).")
        Path out;

        @Override
        public Integer call() throws IOException {
            PrintWriter err = spec.commandLine().getErr();
            Init.Spec source;
            try {
                source = fromSpec == null ? null : Init.Spec.load(fromSpec);
            } catch (IOException | IllegalArgumentException e) {
                err.println("error: --from-spec " + fromSpec + ": " + e.getMessage());
                return ERRORS;
            }
            String title = source == null ? "My API" : source.title();
            String api = Scaffold.apiId(title);
            String server = name != null ? name : api.replace('_', '-') + "-mcp";
            if (!server.matches("[a-z0-9][a-z0-9-]{1,62}")) {
                err.println("error: --name must be lowercase letters, digits and dashes (2 to 63), e.g. orders-mcp");
                return USAGE;
            }
            Path definition = out.resolve(server + ".mcpgp.yaml");
            if (Files.exists(definition)) {
                err.println(definition + " exists; not overwriting it");
                return ERRORS;
            }
            String contractFile = source == null ? "contracts/openapi.yaml" : "contracts/" + source.fileName();
            Files.createDirectories(out.resolve("contracts"));
            if (source != null) {
                Files.write(out.resolve(contractFile), source.bytes());
            }
            Files.writeString(definition, Scaffold.definition(server, title, api, source == null ? "1.0" : source.version(), contractFile));
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("wrote " + definition + (source != null ? " and " + out.resolve(contractFile) : ""));
            if (source == null) {
                writer.println("next: put the API's OpenAPI document at " + out.resolve(contractFile) + ", then run mcpgp validate " + definition);
                return OK;
            }
            Compiler.Result result = new Compiler(new Schemas()).compile(definition, Compiler.Options.defaults());
            if (result.manifest().isPresent()) {
                Path tests = out.resolve("tests/smoke.yaml");
                if (!Files.exists(tests)) {
                    Files.createDirectories(tests.getParent());
                    Files.writeString(tests, Scaffold.smoke(result.manifest().get()));
                    writer.println("wrote " + tests);
                }
                writer.println(result.manifest().get().path("tools").size() + " tools; next: mcpgp test " + definition);
            } else {
                result.diagnostics().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).forEach(d -> writer.println(definition + ": " + d));
                writer.println("the definition needs changes before it builds; see the errors above");
            }
            return OK;
        }

        /** The spec to start from: its bytes, as read, and what the definition takes from it. */
        record Spec(byte[] bytes, String fileName, String title, String version) {
            static Spec load(String from) throws IOException {
                byte[] bytes;
                String fileName;
                if (from.startsWith("https://") || from.startsWith("http://")) {
                    java.net.URI uri = java.net.URI.create(from);
                    if (from.startsWith("http://") && !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())) {
                        throw new IllegalArgumentException("use https (plain http only for localhost)");
                    }
                    try {
                        java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newBuilder()
                                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).connectTimeout(java.time.Duration.ofSeconds(20)).build()
                                .send(java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(60)).GET().build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                        if (response.statusCode() != 200) {
                            throw new IOException("HTTP " + response.statusCode());
                        }
                        bytes = response.body();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                    String path = uri.getPath() == null ? "" : uri.getPath();
                    fileName = path.substring(path.lastIndexOf('/') + 1);
                } else {
                    Path file = Path.of(from);
                    bytes = Files.readAllBytes(file);
                    fileName = file.getFileName().toString();
                }
                JsonNode document;
                try {
                    document = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(bytes);
                } catch (IOException e) {
                    throw new IllegalArgumentException("not YAML or JSON");
                }
                if (document == null || !(document.has("openapi") || document.has("swagger"))) {
                    throw new IllegalArgumentException("not an OpenAPI or Swagger document (no openapi or swagger field)");
                }
                if (!fileName.matches("[A-Za-z0-9._-]+\\.(ya?ml|json)")) {
                    fileName = document.has("swagger") ? "swagger.json" : "openapi.yaml";
                    if (bytes.length > 0 && bytes[0] == '{') {
                        fileName = fileName.replace(".yaml", ".json");
                    }
                }
                String version = document.at("/info/version").asText("1.0");
                return new Spec(bytes, fileName, document.at("/info/title").asText("My API"), version.isBlank() ? "1.0" : version);
            }
        }
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

    @Command(name = "names", description = "Tool names and mcp-names.lock.", subcommands = Names.Accept.class)
    static final class Names {

        @Command(name = "accept", description = "Write the names the policy gives now into mcp-names.lock: renames on purpose, and new names to keep.")
        static final class Accept implements Callable<Integer> {
            @CommandLine.Mixin
            Common common;

            @Parameters(index = "0", paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
            Path definition;

            @Parameters(index = "1..*", arity = "0..*", paramLabel = "<tool>", description = "Tools, by current name, new name or operation id.")
            List<String> tools = new ArrayList<>();

            @Option(names = "--all", description = "Every tool: accept all renames and lock names the file doesn't have yet.")
            boolean all;

            @Override
            public Integer call() throws IOException {
                PrintWriter err = common.spec.commandLine().getErr();
                if (!all && tools.isEmpty()) {
                    err.println("error: name the tools to accept, or give --all");
                    return USAGE;
                }
                Diagnostics workspaceDiagnostics = new Diagnostics();
                Workspace workspace = common.workspace(workspaceDiagnostics);
                Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, false));
                List<Diagnostic> found = new ArrayList<>(workspaceDiagnostics.all());
                found.addAll(result.diagnostics());
                if (result.manifest().isEmpty() || exitCode(found) != OK) {
                    int code = common.report(definition, found.stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList());
                    return code == OK ? ERRORS : code;
                }
                Path root = result.definition().get().root();
                Diagnostics lockDiagnostics = new Diagnostics();
                ro.bitboy.mcpgp.core.tools.NamesLock lock = ro.bitboy.mcpgp.core.tools.NamesLock.load(root, common.schemas, lockDiagnostics);
                if (!lockDiagnostics.all().isEmpty()) {
                    return common.report(definition, lockDiagnostics.all());
                }
                String since = result.manifest().get().at("/server/version").asText().replaceAll("\\+.*$", "");
                java.util.Map<String, ro.bitboy.mcpgp.core.tools.NamesLock.Entry> entries = new java.util.LinkedHashMap<>(lock.entries());
                java.util.Set<String> wanted = new java.util.LinkedHashSet<>(tools);
                PrintWriter out = common.out();
                int renamed = 0;
                int added = 0;
                for (ro.bitboy.mcpgp.core.tools.ToolPlan plan : result.tools()) {
                    String computed = computedName(plan);
                    boolean asked = wanted.remove(plan.name()) | wanted.remove(computed) | wanted.remove(plan.id());
                    if (!all && !asked) {
                        continue;
                    }
                    var locked = entries.get(plan.id());
                    if (locked == null) {
                        entries.put(plan.id(), new ro.bitboy.mcpgp.core.tools.NamesLock.Entry(computed, since));
                        added++;
                        if (!common.quiet) {
                            out.println("locked " + computed + "  (" + plan.id() + ")");
                        }
                    } else if (!locked.name().equals(computed)) {
                        entries.put(plan.id(), new ro.bitboy.mcpgp.core.tools.NamesLock.Entry(computed, since));
                        renamed++;
                        if (!common.quiet) {
                            out.println("renamed " + locked.name() + " → " + computed + "  (clients that call " + locked.name() + " must change)");
                        }
                    }
                }
                if (!wanted.isEmpty()) {
                    err.println("error: no tool named " + String.join(", ", wanted) + " in " + definition);
                    return ERRORS;
                }
                if (renamed == 0 && added == 0) {
                    if (!common.quiet) {
                        out.println("nothing to accept: mcp-names.lock already has these names");
                    }
                    return OK;
                }
                Path file = root.resolve(ro.bitboy.mcpgp.core.tools.NamesLock.FILE_NAME);
                Files.writeString(file, ro.bitboy.mcpgp.core.tools.NamesLock.render(entries));
                if (!common.quiet) {
                    out.println("wrote " + file + " (" + renamed + " renamed, " + added + " added); commit it with the definition");
                }
                return OK;
            }

            /** The name the policy and rules give now: the last name step before the lock. */
            static String computedName(ro.bitboy.mcpgp.core.tools.ToolPlan plan) {
                String name = plan.name();
                for (ro.bitboy.mcpgp.core.tools.ToolPlan.Provenance step : plan.provenance().getOrDefault("name", List.of())) {
                    if (!step.step().equals("lock") && step.value() != null) {
                        name = step.value();
                    }
                }
                return name;
            }
        }
    }

    @Command(name = "test", description = "Run the server against a mock made from the specs: a smoke check and the test cases.")
    static final class Test implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Parameters(paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Option(names = "--suite", paramLabel = "<dir>", description = "Test case files (*.yaml); default: tests/ next to the definition.")
        Path suite;

        @Override
        public Integer call() throws IOException {
            Diagnostics workspaceDiagnostics = new Diagnostics();
            Workspace workspace = common.workspace(workspaceDiagnostics);
            Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, false));
            List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
            all.addAll(result.diagnostics());
            if (result.manifest().isEmpty() || exitCode(all) != OK) {
                int code = common.report(definition, all.stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList());
                return code == OK ? ERRORS : code;
            }
            java.io.InputStream runtime = Packager.runtimeJar();
            if (runtime == null) {
                common.spec.commandLine().getErr().println("error: this CLI carries no runtime to test with");
                return INTERNAL;
            }
            Path cases = suite != null ? suite : definition.toAbsolutePath().getParent().resolve("tests");
            List<Path> files = TestRun.suite(cases);
            if (suite != null && files.isEmpty()) {
                common.spec.commandLine().getErr().println("error: no test case files (*.yaml) in " + suite);
                return USAGE;
            }
            Path dir = Files.createTempDirectory("mcpgp-test-");
            List<TestRun.Outcome> outcomes = new ArrayList<>();
            try (TestRun run = new TestRun(result.manifest().get(), result.contracts().values(), runtime, dir)) {
                String smoke = run.smoke();
                outcomes.add(new TestRun.Outcome("the server starts and lists its tools", "smoke", smoke));
                if (smoke == null) {
                    for (Path file : files) {
                        outcomes.addAll(run.run(file));
                    }
                }
            } finally {
                deleteTree(dir);
            }
            long failed = outcomes.stream().filter(o -> !o.passed()).count();
            PrintWriter out = common.out();
            if (common.json) {
                out.println(CanonicalJson.compact(TestRun.json(outcomes)));
            } else {
                for (TestRun.Outcome o : outcomes) {
                    if (!o.passed()) {
                        out.println("✗ " + o.file() + ": " + o.name() + ": " + o.problem());
                    } else if (!common.quiet) {
                        out.println("✓ " + o.file() + ": " + o.name());
                    }
                }
                if (!common.quiet || failed > 0) {
                    out.println(definition + ": " + (outcomes.size() - failed) + " passed, " + failed + " failed");
                }
            }
            return failed == 0 ? OK : TESTS_FAILED;
        }
    }

    @Command(name = "push", description = "Upload the definition project to a platform as its server's next version; optionally build and release it there.")
    static final class PushCommand implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Parameters(paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Option(names = "--platform", paramLabel = "<url>", description = "The platform's address; default: MCPGP_PLATFORM. The token comes from MCPGP_TOKEN.")
        String platform;

        @Option(names = "--server", paramLabel = "<id>", description = "The server on the platform; default: the definition's metadata.name.")
        String server;

        @Option(names = "--message", paramLabel = "<text>", description = "The version's message, e.g. the commit subject.")
        String message;

        @Option(names = "--build", description = "Build the new version on the platform and wait for the result.")
        boolean build;

        @Option(names = "--verify-local", description = "With --build: fail unless the platform's build gives agents the same as a local compile.")
        boolean verifyLocal;

        @Option(names = "--release", description = "Build, then release (an agent's release waits for a person's approval).")
        boolean release;

        @Option(names = "--timeout", paramLabel = "<seconds>", defaultValue = "600", description = "How long to wait for the build (default: ${DEFAULT-VALUE}).")
        int timeout;

        @Override
        public Integer call() throws IOException {
            PrintWriter err = common.spec.commandLine().getErr();
            String address = platform != null ? platform : env.get("MCPGP_PLATFORM");
            String token = env.get("MCPGP_TOKEN");
            if (address == null || address.isBlank()) {
                err.println("error: give the platform's address with --platform <url> or MCPGP_PLATFORM");
                return USAGE;
            }
            if (token == null || token.isBlank()) {
                err.println("error: set MCPGP_TOKEN to a platform token (a pipeline identity's token in CI)");
                return USAGE;
            }
            Push client;
            try {
                client = new Push(address, token.strip());
            } catch (IllegalArgumentException e) {
                err.println("error: " + e.getMessage());
                return USAGE;
            }
            Diagnostics workspaceDiagnostics = new Diagnostics();
            Workspace workspace = common.workspace(workspaceDiagnostics);
            Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, false));
            List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
            all.addAll(result.diagnostics());
            if (result.manifest().isEmpty() || exitCode(all) != OK) {
                int code = common.report(definition, all.stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList());
                return code == OK ? ERRORS : code;
            }
            JsonNode document = result.definition().orElseThrow().document();
            String id = server != null ? server : document.at("/metadata/name").asText();
            String title = document.at("/metadata/title").asText(id);
            String text = message != null ? message : "pushed with mcpgp " + Compiler.VERSION;
            PrintWriter out = common.out();
            ObjectNode summary = new ObjectMapper().createObjectNode().put("server", id);
            try {
                Map<String, byte[]> files = Push.files(result.definition().get());
                Map.Entry<JsonNode, Boolean> uploaded = client.upload(id, title, text, Push.zip(files));
                int version = uploaded.getKey().path("latestVersion").asInt();
                summary.put("version", version).put("created", uploaded.getValue());
                if (!common.json && !common.quiet) {
                    out.println((uploaded.getValue() ? "created " + id + " with version " : "pushed " + id + ", now at version ") + version
                            + " (" + files.size() + " files)");
                }
                if (!(build || release || verifyLocal)) {
                    return finish(summary, OK);
                }
                JsonNode queued = client.startBuild(id, version);
                String buildId = queued.path("id").asText();
                if (!common.json && !common.quiet) {
                    out.println("building " + buildId + " on the platform…");
                }
                JsonNode done = client.awaitBuild(buildId, java.time.Duration.ofSeconds(timeout));
                summary.put("build", buildId).put("status", done.path("status").asText()).put("serverVersion", done.path("serverVersion").asText(null));
                if (!done.path("status").asText().equals("succeeded")) {
                    err.println("✗ build " + buildId + " failed: " + done.path("error").asText("see the build log"));
                    String log = client.buildLog(buildId).strip();
                    if (!log.isEmpty()) {
                        String[] lines = log.split("\n");
                        err.println(String.join("\n", java.util.Arrays.copyOfRange(lines, Math.max(0, lines.length - 20), lines.length)));
                    }
                    return finish(summary, ERRORS);
                }
                if (!common.json && !common.quiet) {
                    out.println("✓ built " + done.path("serverVersion").asText() + " (" + buildId + ")");
                }
                if (verifyLocal) {
                    JsonNode built = client.builtManifest(done);
                    String local = Push.contentDigest(result.manifest().get());
                    String remote = Push.contentDigest(built);
                    summary.put("localDigest", local).put("platformDigest", remote);
                    if (!local.equals(remote)) {
                        err.println("✗ the platform's build differs from the local compile (" + remote + " vs " + local + "); compiler "
                                + built.at("/build/cli").asText("?") + " on the platform, " + Compiler.VERSION + " here");
                        return finish(summary, ERRORS);
                    }
                    if (!common.json && !common.quiet) {
                        out.println("✓ same as the local compile (" + local + ")");
                    }
                }
                if (release) {
                    JsonNode released = client.release(buildId);
                    if (released.has("approval")) {
                        summary.put("approval", released.at("/approval/id").asText());
                        if (!common.json && !common.quiet) {
                            out.println("release requested: approval " + released.at("/approval/id").asText() + " waits for a person");
                        }
                    } else {
                        summary.put("released", true);
                        if (!common.json && !common.quiet) {
                            out.println("✓ released " + done.path("serverVersion").asText());
                        }
                    }
                }
                return finish(summary, OK);
            } catch (Push.PlatformException e) {
                err.println("error: " + e.getMessage());
                return finish(summary, ERRORS);
            }
        }

        private int finish(ObjectNode summary, int code) {
            if (common.json) {
                common.out().println(CanonicalJson.compact(summary.put("ok", code == OK)));
            }
            return code;
        }
    }

    static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // a temporary directory; the OS cleans it up
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
