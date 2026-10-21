package ro.bitboy.mcpgp.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diff.DiffReport;
import ro.bitboy.mcpgp.core.diff.ManifestDiff;
import ro.bitboy.mcpgp.core.manifest.CanonicalJson;
import ro.bitboy.mcpgp.core.resolve.Workspace;
import ro.bitboy.mcpgp.packager.ExampleConfig;
import ro.bitboy.mcpgp.packager.Packager;
import ro.bitboy.mcpgp.packager.trial.MockRun;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** {@code diff}, {@code preview}, {@code try} and {@code migrate} (cli.md §3.5–3.8). */
final class Inspect {

    static final ObjectMapper JSON = new ObjectMapper();

    private Inspect() {
    }

    /** Compiles a definition for these commands; empty after printing its errors. */
    static Optional<Compiler.Result> compile(Main.Common common, Path definition, Path workspaceRoot) {
        Diagnostics workspaceDiagnostics = new Diagnostics();
        Workspace workspace = workspaceRoot != null
                ? Workspace.load(workspaceRoot.resolve("mcpgp-workspace.yaml"), common.schemas, workspaceDiagnostics)
                : common.workspace(workspaceDiagnostics);
        Compiler.Result result = new Compiler(common.schemas).compile(definition, new Compiler.Options(workspace, false));
        List<Diagnostic> all = new ArrayList<>(workspaceDiagnostics.all());
        all.addAll(result.diagnostics());
        if (result.manifest().isEmpty() || Main.exitCode(all) != Main.OK) {
            common.report(definition, all.stream().filter(d -> d.level() == Diagnostic.Level.ERROR).toList());
            return Optional.empty();
        }
        return Optional.of(result);
    }

    // ---------------------------------------------------------------------- diff

    @Command(name = "diff", description = "What changed since the last release (or another build): tools, their inputs and outputs, names, contracts.")
    static final class Diff implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Mixin
        Main.Common common;

        @Parameters(arity = "0..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Option(names = "--all", description = "Every server in the repository config (.mcpgp/config.yaml).")
        boolean all;

        @Option(names = "--against", paramLabel = "<what>",
                description = "last-release (the latest git tag; the default, or release.baseline), a git tag or commit, "
                        + "a manifest (*.manifest.json), or a folder of them (a release's dist/).")
        String against;

        @Option(names = "--format", paramLabel = "text|md|json", defaultValue = "text", description = "text (default), md (a pull-request comment) or json.")
        String format;

        @Option(names = "--fail-on-breaking", description = "Exit with code 6 when a change breaks agents or clients of the earlier build.")
        boolean failOnBreaking;

        @Override
        public Integer call() throws IOException {
            List<Path> chosen = Main.definitions(common, all, definitions);
            if (chosen == null) {
                return Main.USAGE;
            }
            if (!List.of("text", "md", "json").contains(format)) {
                common.spec.commandLine().getErr().println("error: --format is text, md or json");
                return Main.USAGE;
            }
            String baseline = against != null ? against : Main.repo != null ? Main.repo.baseline() : "last-release";
            PrintWriter out = common.out();
            PrintWriter err = common.spec.commandLine().getErr();
            ArrayNode json = JSON.createArrayNode();
            long breaking = 0;
            int code = Main.OK;
            for (Path definition : chosen) {
                Optional<Compiler.Result> now = compile(common, definition, null);
                if (now.isEmpty()) {
                    code = Main.ERRORS;
                    continue;
                }
                JsonNode after = now.get().manifest().get();
                String server = after.at("/server/name").asText();
                JsonNode before;
                try {
                    before = baseline(definition, server, baseline);
                } catch (IllegalArgumentException e) {
                    err.println("error: " + definition + ": " + e.getMessage());
                    code = Main.ERRORS;
                    continue;
                }
                if (before == null) {
                    // the server didn't exist then: everything is new
                    before = JSON.createObjectNode();
                }
                ManifestDiff.Result diff = ManifestDiff.compare(before, after);
                breaking += diff.breaking();
                switch (format) {
                    case "md" -> out.println(DiffReport.markdown(server, diff));
                    case "json" -> {
                        ObjectNode entry = json.addObject().put("server", server).put("from", diff.from()).put("to", diff.to()).put("breaking", diff.breaking());
                        entry.set("changes", JSON.valueToTree(diff.changes()));
                    }
                    default -> out.print(DiffReport.text(server, diff));
                }
                out.flush(); // print doesn't flush on its own
            }
            if (format.equals("json")) {
                out.println(CanonicalJson.compact(json));
            }
            if (code != Main.OK) {
                return code;
            }
            return failOnBreaking && breaking > 0 ? Main.BREAKING : Main.OK;
        }

        /** The earlier build's manifest: from a file or folder, or compiled from the definition at a git ref; null when the server isn't there. */
        private JsonNode baseline(Path definition, String server, String what) throws IOException {
            Path given = Path.of(what);
            if (Files.isRegularFile(given)) {
                return JSON.readTree(given.toFile());
            }
            if (Files.isDirectory(given)) {
                try (Stream<Path> files = Files.list(given)) {
                    List<Path> manifests = files.filter(f -> f.getFileName().toString().startsWith(server + "-")
                            && f.getFileName().toString().endsWith(".manifest.json")).sorted().toList();
                    if (manifests.isEmpty()) {
                        throw new IllegalArgumentException("no " + server + "-*.manifest.json in " + given);
                    }
                    return JSON.readTree(manifests.get(manifests.size() - 1).toFile());
                }
            }
            String ref = what;
            Path repoRoot = Path.of(git(definition.toAbsolutePath().getParent(), "rev-parse", "--show-toplevel").strip()).toRealPath();
            if (what.equals("last-release")) {
                String tag = gitOrNull(repoRoot, "describe", "--tags", "--abbrev=0");
                if (tag == null) {
                    throw new IllegalArgumentException("no release tag to compare with; tag a release, or pass --against <manifest|ref>");
                }
                ref = tag.strip();
            }
            Path tree = Files.createTempDirectory("mcpgp-diff-");
            try {
                git(repoRoot, "worktree", "add", "--detach", "--quiet", tree.toString(), ref);
                // real paths on both sides (macOS: /var is /private/var), or the relative path walks out of the tree
                Path relative = repoRoot.relativize(definition.toRealPath());
                if (relative.startsWith("..")) {
                    throw new IllegalArgumentException(definition + " isn't inside the git repository " + repoRoot);
                }
                Path then = tree.resolve(relative);
                if (!Files.isRegularFile(then)) {
                    return null;
                }
                Path workspace = Main.repo != null && Main.repo.workspace() != null && Files.isDirectory(Main.repo.workspace())
                        && Main.repo.workspace().toRealPath().startsWith(repoRoot)
                        ? tree.resolve(repoRoot.relativize(Main.repo.workspace().toRealPath())) : null;
                Optional<Compiler.Result> result = compile(common, then, workspace);
                if (result.isEmpty()) {
                    throw new IllegalArgumentException("the definition doesn't compile at " + ref);
                }
                return result.get().manifest().get();
            } finally {
                gitOrNull(repoRoot, "worktree", "remove", "--force", tree.toString());
                Main.deleteTree(tree);
            }
        }

        private static String git(Path dir, String... args) throws IOException {
            String out = gitOrNull(dir, args);
            if (out == null) {
                throw new IllegalArgumentException("git " + String.join(" ", args) + " failed in " + dir + " (is it a git repository?)");
            }
            return out;
        }

        private static String gitOrNull(Path dir, String... args) throws IOException {
            List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return output;
        }
    }

    // ---------------------------------------------------------------------- preview

    @Command(name = "preview", description = "What the agent gets (the tools it sees) and what the customer configures.")
    static final class Preview implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Mixin
        Main.Common common;

        @Parameters(paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Option(names = "--tools", description = "Only the tools.")
        boolean tools;

        @Option(names = "--customer", description = "Only what the customer configures.")
        boolean customer;

        @Option(names = "--format", paramLabel = "md|json|tools-list", defaultValue = "md",
                description = "md (default), json, or tools-list: the result of MCP tools/list (what a client receives), for audits.")
        String format;

        @Override
        public Integer call() {
            Optional<Compiler.Result> result = compile(common, definition, null);
            if (result.isEmpty()) {
                return Main.ERRORS;
            }
            JsonNode manifest = result.get().manifest().get();
            boolean both = !tools && !customer;
            PrintWriter out = common.out();
            if (format.equals("tools-list")) {
                out.println(CanonicalJson.pretty(ro.bitboy.mcpgp.core.manifest.ToolsList.of(manifest)));
                return Main.OK;
            }
            if (format.equals("json")) {
                ObjectNode json = JSON.createObjectNode().put("server", manifest.at("/server/name").asText());
                if (both || tools) {
                    json.set("tools", agentTools(manifest));
                }
                if (both || customer) {
                    json.put("exampleConfig", ExampleConfig.yaml(manifest));
                }
                out.println(CanonicalJson.pretty(json));
                return Main.OK;
            }
            if (!format.equals("md")) {
                common.spec.commandLine().getErr().println("error: --format is md, json or tools-list");
                return Main.USAGE;
            }
            StringBuilder md = new StringBuilder("# ").append(manifest.at("/server/title").asText(manifest.at("/server/name").asText()))
                    .append(" (").append(manifest.at("/server/name").asText()).append(' ').append(manifest.at("/server/version").asText()).append(")\n");
            if (both || tools) {
                JsonNode list = agentTools(manifest);
                String mode = manifest.at("/exposure/mode").asText("plain");
                md.append("\n## What the agent sees\n\n").append(list.size()).append(" tools; exposure `").append(mode).append('`')
                        .append(mode.equals("plain") ? ": every tool is listed" : ": the agent lists some and finds the others by search")
                        .append(".\n\n| Tool | Listed | Does | Inputs (* required) |\n|---|---|---|---|\n");
                for (JsonNode t : list) {
                    md.append("| `").append(t.path("name").asText()).append("` | ").append(t.path("listed").asBoolean() ? "yes" : "by search")
                            .append(" | ").append(cell(t.path("title").asText(t.path("description").asText(""))))
                            .append(t.path("destructive").asBoolean() ? " (destructive)" : t.path("readOnly").asBoolean() ? " (read-only)" : "")
                            .append(" | ").append(cell(String.join(", ", texts(t.path("inputs"))))).append(" |\n");
                }
            }
            if (both || customer) {
                md.append("\n## What the customer configures\n\n`").append(manifest.at("/server/name").asText())
                        .append(".yaml`, from the package's example:\n\n```yaml\n").append(ExampleConfig.yaml(manifest)).append("```\n");
            }
            out.print(md);
            out.flush();
            return Main.OK;
        }

        /** The tools as the agent meets them: listed or found by search, what they do, their inputs. */
        static ArrayNode agentTools(JsonNode manifest) {
            String mode = manifest.at("/exposure/mode").asText("plain");
            List<String> pinned = texts(manifest.at("/exposure/pinned"));
            ArrayNode list = JSON.createArrayNode();
            for (JsonNode tool : manifest.path("tools")) {
                boolean api = tool.path("kind").asText().equals("api");
                boolean listed = switch (mode) {
                    case "hybrid" -> !api || pinned.contains(tool.path("name").asText());
                    case "discovery", "toolsets" -> !api;
                    default -> true;
                };
                ObjectNode entry = list.addObject().put("name", tool.path("name").asText()).put("kind", tool.path("kind").asText())
                        .put("listed", listed).put("title", tool.path("title").asText("")).put("description", tool.path("description").asText(""))
                        .put("readOnly", tool.at("/annotations/readOnlyHint").asBoolean(false))
                        .put("destructive", tool.at("/annotations/destructiveHint").asBoolean(false));
                ArrayNode inputs = entry.putArray("inputs");
                JsonNode schema = tool.path("inputSchema");
                List<String> required = texts(schema.path("required"));
                schema.path("properties").fields().forEachRemaining(group -> {
                    JsonNode properties = group.getValue().path("properties");
                    if (properties.isObject() && !properties.isEmpty()) {
                        List<String> inner = texts(group.getValue().path("required"));
                        properties.fieldNames().forEachRemaining(p -> inputs.add(group.getKey() + "." + p + (inner.contains(p) ? "*" : "")));
                    } else {
                        inputs.add(group.getKey() + (required.contains(group.getKey()) ? "*" : ""));
                    }
                });
            }
            return list;
        }

        private static String cell(String text) {
            String one = text.replace("|", "\\|").replace("\n", " ");
            return one.length() > 120 ? one.substring(0, 119) + "…" : one;
        }
    }

    // ---------------------------------------------------------------------- try

    @Command(name = "try", description = "Call a tool as an agent would: against a real target server your machine reaches, or against a mock.")
    static final class Try implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Mixin
        Main.Common common;

        @Parameters(paramLabel = "<definition>", description = "Root definition file (*.mcpgp.yaml).")
        Path definition;

        @Option(names = "--target", paramLabel = "<url>", description = "The target server's address (no credentials; use --runtime-config for them).")
        String target;

        @Option(names = "--runtime-config", paramLabel = "<file>",
                description = "A runtime config (as the customer writes it) with the instances and their credentials, secrets as ${env:…}.")
        Path runtimeConfig;

        @Option(names = "--mock", description = "Against a mock made from the specs, as the platform's Try does.")
        boolean mock;

        @Option(names = "--tool", paramLabel = "<name>", description = "The tool to call; without it, the tools are listed.")
        String tool;

        @Option(names = "--args", paramLabel = "<json>", defaultValue = "{}", description = "The arguments, a JSON object.")
        String args;

        @Override
        public Integer call() throws IOException {
            PrintWriter err = common.spec.commandLine().getErr();
            int sources = (target != null ? 1 : 0) + (runtimeConfig != null ? 1 : 0) + (mock ? 1 : 0);
            if (sources != 1) {
                err.println("error: give one of --target <url>, --runtime-config <file> or --mock");
                return Main.USAGE;
            }
            Map<String, Object> arguments;
            try {
                JsonNode parsed = JSON.readTree(args);
                if (!parsed.isObject()) {
                    throw new IOException("not an object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JSON.convertValue(parsed, LinkedHashMap.class);
                arguments = map;
            } catch (IOException e) {
                err.println("error: --args is a JSON object, e.g. '{\"path\": {\"id\": \"c-1\"}}'");
                return Main.USAGE;
            }
            Optional<Compiler.Result> result = compile(common, definition, null);
            if (result.isEmpty()) {
                return Main.ERRORS;
            }
            JsonNode manifest = result.get().manifest().get();
            java.io.InputStream runtime = Packager.runtimeJar();
            if (runtime == null) {
                err.println("error: this CLI carries no runtime to run the server with");
                return Main.INTERNAL;
            }
            Path dir = Files.createTempDirectory("mcpgp-try-");
            try (MockRun run = mock
                    ? new MockRun(manifest, result.get().contracts().values(), runtime, dir, result.get().definition().get().root())
                    : MockRun.against(manifest, config(manifest), runtime, dir, result.get().definition().get().root())) {
                String smoke = run.smoke();
                if (smoke != null) {
                    err.println("error: the server didn't start: " + smoke);
                    return Main.ERRORS;
                }
                PrintWriter out = common.out();
                if (tool == null) {
                    ArrayNode tools = Preview.agentTools(manifest);
                    if (common.json) {
                        out.println(CanonicalJson.compact(tools));
                    } else {
                        tools.forEach(t -> out.println(t.path("name").asText() + (t.path("listed").asBoolean() ? "" : "  (by search)")
                                + "  " + t.path("title").asText("")));
                        out.println("call one: mcpgp try " + definition + " " + (mock ? "--mock" : target != null ? "--target " + target
                                : "--runtime-config " + runtimeConfig) + " --tool <name> --args '{…}'");
                    }
                    return Main.OK;
                }
                if (mock && manifest.at("/instances/mode").asText().equals("multi")) {
                    arguments.putIfAbsent("instance", MockRun.INSTANCE);
                }
                if (target != null && manifest.at("/instances/mode").asText().equals("multi")) {
                    arguments.putIfAbsent("instance", MockRun.INSTANCE);
                }
                JsonNode answer = run.call(tool, arguments);
                if (common.json) {
                    out.println(CanonicalJson.compact(answer));
                } else {
                    print(out, answer);
                }
                boolean failed = answer.has("error") || answer.at("/result/isError").asBoolean(false);
                return failed ? Main.ERRORS : Main.OK;
            } finally {
                Main.deleteTree(dir);
            }
        }

        /** The runtime config to run with: the one given (YAML or JSON), or every instance at the target. */
        private JsonNode config(JsonNode manifest) throws IOException {
            if (runtimeConfig != null) {
                JsonNode given = new YAMLMapper().readTree(runtimeConfig.toFile());
                ObjectNode config = given.deepCopy();
                // the CLI talks to the server over stdio, whatever the file says
                config.with("server").put("transport", "stdio");
                return config;
            }
            return MockRun.configAt(manifest, target);
        }

        private static void print(PrintWriter out, JsonNode answer) {
            if (answer.has("error")) {
                out.println("✗ " + answer.at("/error/message").asText(answer.path("error").toString()));
                return;
            }
            JsonNode result = answer.path("result");
            boolean error = result.path("isError").asBoolean(false);
            JsonNode structured = result.path("structuredContent");
            if (!structured.isMissingNode() && !structured.isNull()) {
                out.println((error ? "✗ " : "") + CanonicalJson.pretty(structured));
            } else {
                for (JsonNode c : result.path("content")) {
                    if (c.path("type").asText().equals("text")) {
                        out.println((error ? "✗ " : "") + c.path("text").asText());
                    }
                }
            }
            for (JsonNode r : answer.path("requests")) {
                out.println("→ " + r.path("method").asText() + " " + r.path("path").asText()
                        + (r.path("query").asText("").isEmpty() ? "" : "?" + r.path("query").asText()) + "  " + r.path("status").asText(""));
            }
        }
    }

    // ---------------------------------------------------------------------- migrate

    /** The definition formats ({@code apiVersion}) this CLI reads, oldest first; the last is current. */
    static final List<String> FORMATS = List.of("mcpgp/v1");

    @Command(name = "migrate", description = "Upgrade definitions to the current format (apiVersion). There is one format so far: nothing to do yet.")
    static final class Migrate implements java.util.concurrent.Callable<Integer> {
        @CommandLine.Mixin
        Main.Common common;

        @Parameters(arity = "0..*", paramLabel = "<definition>", description = "Root definition files (*.mcpgp.yaml).")
        List<Path> definitions;

        @Option(names = "--all", description = "Every server in the repository config (.mcpgp/config.yaml).")
        boolean all;

        @Override
        public Integer call() throws IOException {
            List<Path> chosen = Main.definitions(common, all, definitions);
            if (chosen == null) {
                return Main.USAGE;
            }
            String current = FORMATS.get(FORMATS.size() - 1);
            int code = Main.OK;
            for (Path definition : chosen) {
                JsonNode doc;
                try {
                    doc = new YAMLMapper().readTree(definition.toFile());
                } catch (IOException e) {
                    common.spec.commandLine().getErr().println("error: " + definition + ": can't be read");
                    code = Main.ERRORS;
                    continue;
                }
                String version = doc == null ? "" : doc.path("apiVersion").asText("");
                if (version.equals(current)) {
                    if (!common.quiet) {
                        common.out().println(definition + ": already " + current + " (the current format); nothing to change");
                    }
                } else if (FORMATS.contains(version)) {
                    common.out().println(definition + ": " + version + " → " + current);
                } else {
                    common.spec.commandLine().getErr().println("error: " + definition + ": apiVersion '" + version
                            + "' isn't one this CLI knows (" + String.join(", ", FORMATS) + "); a newer CLI may");
                    code = Main.ERRORS;
                }
            }
            return code;
        }
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }
}
