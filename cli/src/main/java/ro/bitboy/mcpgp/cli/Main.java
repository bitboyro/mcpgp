package ro.bitboy.mcpgp.cli;

import ro.bitboy.mcpgp.core.scaffold.Scaffold;

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
import ro.bitboy.mcpgp.packager.trial.MockRun;
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
@Command(name = "mcpgp", mixinStandardHelpOptions = true, scope = CommandLine.ScopeType.INHERIT, version = Compiler.VERSION,
        description = "Generate MCP servers from OpenAPI contracts.",
        subcommands = {Main.Init.class, Main.Validate.class, Main.Build.class, Main.Explain.class, Main.Names.class, Main.Test.class, Main.PushCommand.class, Main.Keygen.class, Main.Sign.class, Main.Verify.class,
                Inspect.Diff.class, Inspect.Preview.class, Inspect.Try.class, Inspect.Migrate.class})
public final class Main {

    static final int OK = 0;
    static final int ERRORS = 1;
    static final int USAGE = 2;
    static final int NAME_DRIFT = 3;
    static final int TESTS_FAILED = 4;
    static final int VERSION_MISMATCH = 5;
    /** {@code diff --fail-on-breaking}: a change breaks agents or clients of the earlier build. */
    static final int BREAKING = 6;
    static final int INTERNAL = 10;

    /** Environment, replaceable in tests. */
    static Map<String, String> env = System.getenv();

    /** Where the repository config is looked for (the working directory), replaceable in tests. */
    static Path cwd = Path.of("");

    /** The repository config of this run ({@code .mcpgp/config.yaml}), or null. */
    static RepoConfig repo;

    @Option(names = "--config", paramLabel = "<file>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository config; default: the nearest .mcpgp/config.yaml.")
    Path config;

    @Option(names = "--no-color", scope = CommandLine.ScopeType.INHERIT,
            description = "No colour in the output (the output has none so far; NO_COLOR is honoured the same way).")
    boolean noColor;

    @Option(names = "--ignore-version", scope = CommandLine.ScopeType.INHERIT,
            description = "Run although the CLI version differs from cli: in .mcpgp/config.yaml.")
    boolean ignoreVersion;

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
        cli.setExecutionStrategy(parseResult -> {
            Integer help = CommandLine.executeHelpRequest(parseResult);
            if (help != null) {
                return help;
            }
            Main main = parseResult.commandSpec().commandLine().getCommand();
            int checked = loadRepo(main, err);
            return checked != OK ? checked : new CommandLine.RunLast().execute(parseResult);
        });
        return cli.execute(args);
    }

    /** Reads the repository config and checks the CLI version it pins (cli.md §1, exit code 5). */
    static int loadRepo(Main main, PrintWriter err) {
        try {
            repo = RepoConfig.find(main.config, cwd);
        } catch (IOException | IllegalArgumentException e) {
            err.println("error: " + (e instanceof IOException ? "can't read the repository config: " : "") + e.getMessage());
            repo = null;
            return ERRORS;
        }
        if (repo == null) {
            return OK;
        }
        repo.notYet().forEach(w -> err.println("warning: " + w));
        if (!repo.cli().equals(Compiler.VERSION) && !main.ignoreVersion) {
            err.println("error: " + repo.file() + " pins mcpgp " + repo.cli() + ", this is " + Compiler.VERSION
                    + ". Use mcpgp " + repo.cli() + ", change cli: in the config, or pass --ignore-version.");
            return VERSION_MISMATCH;
        }
        return OK;
    }

    /** The servers of {@code --all}: those listed in the repository config. */
    static List<Path> allServers(PrintWriter err) {
        if (repo == null) {
            err.println("error: --all needs a repository config (" + RepoConfig.FILE + " with servers:), or --config <file>");
            return null;
        }
        return repo.servers();
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

        /** {@code --workspace}, else the repository config's {@code workspace}, else none. */
        Workspace workspace(Diagnostics diagnostics) {
            Path dir = workspace != null ? workspace : repo != null ? repo.workspace() : null;
            return dir == null ? Workspace.none()
                    : Workspace.load(dir.resolve("mcpgp-workspace.yaml"), schemas, diagnostics);
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

    /** The definitions a command works on: the ones named, or with {@code --all} the repository config's; null after a usage error. */
    static List<Path> definitions(Common common, boolean all, List<Path> named) {
        PrintWriter err = common.spec.commandLine().getErr();
        boolean any = named != null && !named.isEmpty();
        if (all && any) {
            err.println("error: give definitions or --all, not both");
            return null;
        }
        if (all) {
            return allServers(err);
        }
        if (!any) {
            err.println("error: give a definition (*.mcpgp.yaml), or --all for the servers in " + RepoConfig.FILE);
            return null;
        }
        return named;
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

        @Option(names = "--tag", paramLabel = "<tag>", split = ",", description = "Only operations with this tag (repeatable, or comma-separated); for a big API.")
        List<String> tags = List.of();

        @Option(names = "--path", paramLabel = "<glob>", split = ",", description = "Only operations whose path matches, e.g. '/messages/**' (repeatable).")
        List<String> paths = List.of();

        @Option(names = "--access", paramLabel = "<read-only|read-write|all>", description = "Which methods become tools (the definition's default when not given).")
        String access;

        @Option(names = "--collisions", paramLabel = "<error|method-path|suffix-number|suffix-contract>", defaultValue = "method-path",
                description = "Two operations that would get the same tool name: default ${DEFAULT-VALUE} (named from method and path), error stops the build.")
        String collisions;

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
            Map<String, byte[]> contracts = new java.util.LinkedHashMap<>();
            if (source != null) {
                // the spec and the files it refers to ($ref), as the platform takes them (SpecFiles)
                Map<String, byte[]> files = source.files();
                ro.bitboy.mcpgp.core.scaffold.SpecFiles.Analysis analysis = ro.bitboy.mcpgp.core.scaffold.SpecFiles.analyze(files, source.fileName());
                if (!analysis.ok()) {
                    analysis.missing().forEach(m -> err.println("error: " + m.target() + " is missing (referenced from " + m.from() + " at " + m.pointer() + ")"));
                    analysis.broken().forEach(b -> err.println("error: " + b.from() + " refers to " + b.target() + " (at " + b.pointer() + "), which isn't there"));
                    analysis.remote().forEach(r -> err.println("error: " + r.from() + " refers to " + r.target()
                            + "; builds don't fetch URLs: download that file next to the spec and refer to it by its path"));
                    analysis.problems().forEach(p -> err.println("error: " + p));
                    return ERRORS;
                }
                contracts.putAll(ro.bitboy.mcpgp.core.scaffold.SpecFiles.under("contracts/", files, analysis));
                contractFile = "contracts/" + analysis.main();
            }
            Files.createDirectories(out.resolve("contracts"));
            for (Map.Entry<String, byte[]> file : contracts.entrySet()) {
                Path target = out.resolve(file.getKey()).normalize();
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            if (access != null && !List.of("read-only", "read-write", "all").contains(access)
                    || !List.of("error", "method-path", "suffix-number", "suffix-contract").contains(collisions)) {
                err.println("error: --access is read-only, read-write or all; --collisions is error, method-path, suffix-number or suffix-contract");
                return USAGE;
            }
            Files.writeString(definition, Scaffold.definition(server, title, api, source == null ? "1.0" : source.version(), contractFile,
                    tags, paths, access, source == null || collisions.equals("error") ? null : collisions));
            PrintWriter writer = spec.commandLine().getOut();
            writer.println("wrote " + definition + (source != null ? " and " + out.resolve(contractFile)
                    + (contracts.size() > 1 ? " with the " + (contracts.size() - 1) + " file" + (contracts.size() == 2 ? "" : "s") + " it refers to" : "") : ""));
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
                List<Diagnostic> errors = result.diagnostics().stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList();
                errors.stream().limit(20).forEach(d -> writer.println(definition + ": " + d));
                if (errors.size() > 20) {
                    writer.println("… and " + (errors.size() - 20) + " more errors");
                }
                writer.println("the definition needs changes before it builds; see the errors above"
                        + (errors.size() > 20 ? " (for a big API, start smaller: --tag, --path or --access)" : ""));
            }
            return OK;
        }

        /**
         * The spec to start from: its bytes, as read, and what the definition takes from it; for a local
         * file, also the files it refers to ({@code $ref}s, followed from file to file), by path relative
         * to the spec's folder.
         */
        record Spec(byte[] bytes, String fileName, String title, String version, Path file) {
            Map<String, byte[]> files() throws IOException {
                Map<String, byte[]> files = new java.util.TreeMap<>();
                files.put(fileName, bytes);
                if (file == null) {
                    return files;
                }
                Path root = file.toAbsolutePath().getParent();
                java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>(List.of(fileName));
                com.fasterxml.jackson.dataformat.yaml.YAMLMapper yaml = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
                while (!queue.isEmpty() && files.size() < 5000) {
                    String current = queue.poll();
                    JsonNode document;
                    try {
                        document = yaml.readTree(files.get(current));
                    } catch (IOException e) {
                        continue; // reported by the analysis
                    }
                    List<String> refs = new java.util.ArrayList<>();
                    refs(document, refs);
                    for (String ref : refs) {
                        String part = ref.contains("#") ? ref.substring(0, ref.indexOf('#')) : ref;
                        if (part.isEmpty() || part.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
                            continue;
                        }
                        Path parent = Path.of(current).getParent();
                        Path target = (parent == null ? Path.of(part) : parent.resolve(part)).normalize();
                        String key = target.toString().replace('\\', '/');
                        Path onDisk = root.resolve(target).normalize();
                        if (key.startsWith("..") || files.containsKey(key) || !onDisk.startsWith(root) || !Files.isRegularFile(onDisk)) {
                            continue; // missing or outside the spec's folder: the analysis says so
                        }
                        files.put(key, Files.readAllBytes(onDisk));
                        queue.add(key);
                    }
                }
                return files;
            }

            private static void refs(JsonNode node, List<String> into) {
                if (node == null) {
                    return;
                }
                if (node.isObject() && node.path("$ref").isTextual()) {
                    into.add(node.get("$ref").asText());
                }
                node.forEach(child -> refs(child, into));
            }

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
                return new Spec(bytes, fileName, document.at("/info/title").asText("My API"), version.isBlank() ? "1.0" : version,
                        from.startsWith("https://") || from.startsWith("http://") ? null : Path.of(from));
            }
        }
    }

    @Command(name = "validate", description = "Schema and compiler checks; writes nothing.")
    static final class Validate implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Option(names = "--frozen-lock", description = "Fail when a tool name would differ from mcp-names.lock.")
        boolean frozenLock;

        @Parameters(arity = "0..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Option(names = "--all", description = "Every server in the repository config (.mcpgp/config.yaml).")
        boolean all;

        @Override
        public Integer call() {
            List<Path> chosen = definitions(common, all, definitions);
            if (chosen == null) {
                return USAGE;
            }
            int code = OK;
            for (Path definition : chosen) {
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

    @Command(name = "build", description = "Compile and package definitions.")
    static final class Build implements Callable<Integer> {
        @CommandLine.Mixin
        Common common;

        @Parameters(arity = "0..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Option(names = "--all", description = "Every server in the repository config (.mcpgp/config.yaml).")
        boolean all;

        @Option(names = "--manifest-only", description = "Write only the manifest, no package.")
        boolean manifestOnly;

        @Option(names = "--out", paramLabel = "<dir>", description = "Output directory (default: out: in the repository config, else dist).")
        Path out;

        @Option(names = "--version", paramLabel = "<v>", description = "Server version; default: the default contract's version.")
        String version;

        @Option(names = "--build-number", paramLabel = "<n>", description = "Added as SemVer build metadata, e.g. 2.0.0+3.")
        Integer buildNumber;

        @Option(names = "--source", paramLabel = "<uri>", description = "Where the definition comes from, e.g. git+https://…#<commit>.")
        String source;

        @Option(names = "--frozen-lock", description = "Fail when a tool name would differ from mcp-names.lock (exit code 3).")
        boolean frozenLock;

        @Option(names = "--no-output-schemas", description = "Build without tools' output schemas, whatever schemas.output says.")
        boolean noOutputSchemas;

        @Option(names = "--include-excluded-diagnostics", description = "Include excluded-operation diagnostics in the manifest.")
        boolean includeExcludedDiagnostics;

        @Override
        public Integer call() throws IOException {
            List<Path> chosen = definitions(common, all, definitions);
            if (chosen == null) {
                return USAGE;
            }
            Path dir = out != null ? out : repo != null && repo.out() != null ? repo.out() : Path.of("dist");
            int code = OK;
            for (Path definition : chosen) {
                code = Math.max(code, build(definition, dir));
            }
            return code;
        }

        private int build(Path definition, Path out) throws IOException {
            Diagnostics workspaceDiagnostics = new Diagnostics();
            Workspace workspace = common.workspace(workspaceDiagnostics);
            ManifestWriter.Build build = new ManifestWriter.Build(createdAt(definition), Compiler.VERSION, version, buildNumber, source);
            Compiler.Result result = new Compiler(common.schemas).compile(definition,
                    new Compiler.Options(workspace, frozenLock, build, noOutputSchemas ? Boolean.FALSE : null, includeExcludedDiagnostics));
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
                    .build(manifest, document, Packager.Artifacts.in(result.definition().get().root()), build.createdAt(), out, signing);
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
                    if (step.has("from")) {
                        from.add("from " + step.path("from").asText());     // a shared item, not a line of this project
                    } else if (step.has("file")) {
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

        @Parameters(arity = "0..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Option(names = "--all", description = "Every server in the repository config (.mcpgp/config.yaml), each with its own tests/.")
        boolean all;

        @Option(names = "--suite", paramLabel = "<dir>", description = "Test case files (*.yaml); default: tests/ next to the definition.")
        Path suite;

        @Override
        public Integer call() throws IOException {
            List<Path> chosen = definitions(common, all, definitions);
            if (chosen == null) {
                return USAGE;
            }
            if (suite != null && chosen.size() > 1) {
                common.spec.commandLine().getErr().println("error: --suite applies to one definition; with several, each uses its own tests/");
                return USAGE;
            }
            int code = OK;
            for (Path definition : chosen) {
                code = Math.max(code, test(definition));
            }
            return code;
        }

        private int test(Path definition) throws IOException {
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
            List<Path> files = MockRun.suite(cases);
            if (suite != null && files.isEmpty()) {
                common.spec.commandLine().getErr().println("error: no test case files (*.yaml) in " + suite);
                return USAGE;
            }
            Path dir = Files.createTempDirectory("mcpgp-test-");
            List<MockRun.Outcome> outcomes = new ArrayList<>();
            try (MockRun run = new MockRun(result.manifest().get(), result.contracts().values(), runtime, dir, result.definition().get().root())) {
                String smoke = run.smoke();
                outcomes.add(new MockRun.Outcome("the server starts and lists its tools", "smoke", smoke));
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
                out.println(CanonicalJson.compact(MockRun.json(outcomes)));
            } else {
                for (MockRun.Outcome o : outcomes) {
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

    @Command(name = "sign", description = "Sign a release directory: SHA256SUMS over its files and SHA256SUMS.sig (minisign), for what you publish.")
    static final class Sign implements Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Parameters(paramLabel = "<dir>", description = "The directory to sign, e.g. dist/ after mcpgp build.")
        Path dir;

        @Option(names = "--key", paramLabel = "<ref>",
                description = "The secret key as a reference: ${env:NAME} or ${file:path}. Default: sign.key in the repository config, else ${env:MCPGP_SIGNING_KEY}.")
        String key;

        @Override
        public Integer call() throws Exception {
            PrintWriter err = spec.commandLine().getErr();
            if (!Files.isDirectory(dir)) {
                err.println("error: " + dir + " is not a directory");
                return USAGE;
            }
            String reference = key != null ? key : repo != null && repo.signKey() != null ? repo.signKey() : "${env:MCPGP_SIGNING_KEY}";
            Minisign.Keys keys;
            try {
                keys = signingKey(com.fasterxml.jackson.databind.node.TextNode.valueOf(reference), repo != null ? repo.root() : cwd.toAbsolutePath());
            } catch (IllegalArgumentException e) {
                err.println("error: the signing key (" + reference + "): " + e.getMessage());
                return ERRORS;
            }
            java.util.TreeMap<String, byte[]> files = releaseFiles(dir);
            if (files.isEmpty()) {
                err.println("error: " + dir + " has no files to sign");
                return ERRORS;
            }
            StringBuilder sums = new StringBuilder();
            files.forEach((name, data) -> sums.append(Packager.sha256(data)).append("  ").append(name).append('\n'));
            byte[] text = sums.toString().getBytes(StandardCharsets.UTF_8);
            String comment = "timestamp:" + createdAt(dir.resolve("SHA256SUMS")).getEpochSecond() + "\tfile:SHA256SUMS";
            Files.write(dir.resolve("SHA256SUMS"), text);
            Files.writeString(dir.resolve("SHA256SUMS.sig"), Minisign.sign(keys, text, comment));
            spec.commandLine().getOut().println("signed " + files.size() + " files in " + dir + " (SHA256SUMS, SHA256SUMS.sig; key id "
                    + Minisign.keyIdText(keys.keyId()) + "); check with mcpgp verify " + dir + " --key mcpgp.pub");
            return OK;
        }
    }

    /** The files of a release directory (not in subfolders), without its own SHA256SUMS and signature. */
    static java.util.TreeMap<String, byte[]> releaseFiles(Path dir) throws IOException {
        java.util.TreeMap<String, byte[]> files = new java.util.TreeMap<>();
        try (java.util.stream.Stream<Path> list = Files.list(dir)) {
            for (Path file : list.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                if (!name.equals("SHA256SUMS") && !name.equals("SHA256SUMS.sig")) {
                    files.put(name, Files.readAllBytes(file));
                }
            }
        }
        return files;
    }

    @Command(name = "verify", description = "Verify a package, or a directory signed with mcpgp sign: the signature of SHA256SUMS and every file's checksum.")
    static final class Verify implements Callable<Integer> {
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Parameters(paramLabel = "<zip|dir>", description = "The package, or a directory signed with mcpgp sign.")
        Path zip;

        @Option(names = "--key", paramLabel = "<pub>", required = true,
                description = "The builder's public key, as published by the builder (not only the copy in the package).")
        Path key;

        @Override
        public Integer call() throws Exception {
            PrintWriter writer = spec.commandLine().getOut();
            java.util.Map<String, byte[]> entries = new java.util.TreeMap<>();
            if (Files.isDirectory(zip)) {
                entries.putAll(releaseFiles(zip));
                for (String own : List.of("SHA256SUMS", "SHA256SUMS.sig")) {
                    if (Files.isRegularFile(zip.resolve(own))) {
                        entries.put(own, Files.readAllBytes(zip.resolve(own)));
                    }
                }
            } else {
                try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
                    for (java.util.zip.ZipEntry entry : java.util.Collections.list(file.entries())) {
                        entries.put(entry.getName(), file.getInputStream(entry).readAllBytes());
                    }
                }
            }
            byte[] sums = entries.get("SHA256SUMS");
            byte[] sig = entries.get("SHA256SUMS.sig");
            if (sums == null || sig == null) {
                writer.println("✗ " + (Files.isDirectory(zip) ? "the directory" : "the package") + " has no "
                        + (sums == null ? "SHA256SUMS" : "SHA256SUMS.sig"));
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
            writer.println(ok ? "✓ all " + listed.size() + " files match SHA256SUMS" : "✗ " + (Files.isDirectory(zip) ? "the directory" : "the package") + " was changed");
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
