package ro.bitboy.mcpgp.core.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostic;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractFiles;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.SourcedRule;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns a definition project into a {@link ResolvedDefinition} (definition-format §7):
 * validates every file against its schema, inlines {@code $ref}s, collects rule files from
 * {@code overrides.include}, merges the naming policy and, for variants, the parent definition, and
 * resolves contract and overlay paths — never outside the project root, never over the network.
 */
public final class DefinitionResolver {

    private static final String RESOLVED_SPEC = "$resolvedSpec";
    private static final String RESOLVED_OVERLAYS = "$resolvedOverlays";

    private final Schemas schemas;

    public DefinitionResolver(Schemas schemas) {
        this.schemas = schemas;
    }

    /** The outcome: a resolved definition when there were no errors, and every diagnostic. */
    public record Result(Optional<ResolvedDefinition> definition, List<Diagnostic> diagnostics) {
    }

    public Result resolve(Path definitionFile, Workspace workspace) {
        Path file = definitionFile.toAbsolutePath().normalize();
        Run run = new Run(file.getParent(), workspace);
        Optional<Partial> partial = run.definition(file);
        if (partial.isEmpty() || run.diagnostics.hasErrors()) {
            return new Result(Optional.empty(), run.diagnostics.all());
        }
        Optional<ResolvedDefinition> resolved = run.finish(file, partial.get());
        return new Result(run.diagnostics.hasErrors() ? Optional.empty() : resolved, run.diagnostics.all());
    }

    /** A definition file after its own resolution and its parent's merge. */
    private record Partial(ObjectNode document, List<SourcedRule> rules, String parent, Map<String, String> noticeSources) {
    }

    /** State of one resolution run. */
    private final class Run {
        private final Path primaryRoot;
        private final Workspace workspace;
        private final Diagnostics diagnostics = new Diagnostics();
        private final Deque<Path> stack = new ArrayDeque<>();
        private final Set<Path> files = new LinkedHashSet<>();

        Run(Path primaryRoot, Workspace workspace) {
            this.primaryRoot = primaryRoot;
            this.workspace = workspace;
        }

        // ---------------------------------------------------------------- definitions

        Optional<Partial> definition(Path file) {
            Location location = location(file);
            if (!enter(file, location)) {
                return Optional.empty();
            }
            try {
                Optional<ObjectNode> read = readValidated(file, SchemaKind.DEFINITION, Codes.DEFINITION_INVALID, location);
                if (read.isEmpty()) {
                    return Optional.empty();
                }
                Path root = file.getParent();
                ObjectNode document = read.get();
                normalizeApi(document);
                if (!checkUniqueApiIds(document, location)) {
                    return Optional.empty();
                }
                JsonNode expanded = expandReferences(document, file, root, "");
                document = (ObjectNode) expanded;
                if (!renameDownstreamAuth(document, location)) {
                    return Optional.empty();
                }

                resolveNaming(document, file, root, location);
                Set<String> ownNotices = new HashSet<>();
                document.at("/descriptions/notices").forEach(n -> {
                    if (n.hasNonNull("id")) {
                        ownNotices.add(n.get("id").asText());
                    }
                });
                Map<String, String> noticeSources = resolveNotices(document, file, root, location);
                List<SourcedRule> rules = collectRules(document.path("overrides"), file, root, location);
                document.remove("overrides");
                resolveContracts(document, file, root, location);

                String parent = null;
                JsonNode extendsNode = document.remove("extends");
                if (extendsNode != null) {
                    Location extendsLocation = location.withPointer("/extends");
                    Optional<Path> parentFile = reference(extendsNode.asText(), file, root, extendsLocation);
                    if (parentFile.isEmpty()) {
                        return Optional.empty();
                    }
                    Optional<Partial> parentPartial = definition(parentFile.get());
                    if (parentPartial.isEmpty()) {
                        return Optional.empty();
                    }
                    Partial p = parentPartial.get();
                    parent = p.document().path("metadata").path("name").asText(null);
                    document = (ObjectNode) new Merger(diagnostics, location).merge(p.document(), document);
                    List<SourcedRule> combined = new ArrayList<>(p.rules());
                    combined.addAll(rules);
                    rules = combined;
                    // the parent's included notices stay included, unless this definition replaces them
                    Map<String, String> sources = new LinkedHashMap<>(p.noticeSources());
                    sources.keySet().removeAll(ownNotices);
                    sources.putAll(noticeSources);
                    noticeSources = sources;
                }
                return Optional.of(new Partial(document, rules, parent, Map.copyOf(noticeSources)));
            } finally {
                stack.pop();
            }
        }

        Optional<ResolvedDefinition> finish(Path file, Partial partial) {
            Location location = location(file);
            ObjectNode document = partial.document();
            Map<ContractKey, ContractFiles> contracts = new LinkedHashMap<>();
            ArrayNode apis = (ArrayNode) document.path("apis");
            for (int i = 0; i < apis.size(); i++) {
                ObjectNode api = (ObjectNode) apis.get(i);
                String apiId = api.path("id").asText();
                ArrayNode list = (ArrayNode) api.path("contracts");
                int defaults = 0;
                for (int j = 0; j < list.size(); j++) {
                    ObjectNode contract = (ObjectNode) list.get(j);
                    String pointer = "/apis/" + i + "/contracts/" + j;
                    JsonNode spec = contract.remove(RESOLVED_SPEC);
                    JsonNode overlays = contract.remove(RESOLVED_OVERLAYS);
                    if (contract.path("default").asBoolean(false)) {
                        defaults++;
                    }
                    if (spec == null) {
                        diagnostics.error(Codes.DEFINITION_INVALID,
                                "Contract " + apiId + "@" + contract.path("version").asText() + " has no spec",
                                location.withPointer(pointer));
                        continue;
                    }
                    List<Path> overlayFiles = new ArrayList<>();
                    if (overlays != null) {
                        overlays.forEach(o -> overlayFiles.add(Path.of(o.asText())));
                    }
                    contracts.put(new ContractKey(apiId, contract.path("version").asText()),
                            new ContractFiles(Path.of(spec.asText()), List.copyOf(overlayFiles)));
                }
                if (defaults > 1) {
                    diagnostics.error(Codes.DEFAULT_CONTRACT_AMBIGUOUS,
                            "API " + apiId + " marks " + defaults + " contracts as default", location.withPointer("/apis/" + i + "/contracts"));
                }
            }

            for (Schemas.Problem problem : schemas.problems(SchemaKind.DEFINITION, document)) {
                diagnostics.error(Codes.DEFINITION_INVALID, problem.message() + " (after merging)",
                        location.withPointer(problem.pointer()));
            }
            if (diagnostics.hasErrors()) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedDefinition(file, primaryRoot, document, List.copyOf(partial.rules()),
                    Map.copyOf(contracts), partial.parent(), List.copyOf(files), partial.noticeSources()));
        }

        // ---------------------------------------------------------------- pieces

        /**
         * {@code apis[].downstreamAuth}, the name before 0.4.0, becomes {@code upstreamAuth} in each file before
         * merging, so a parent and a child can use either name. Warns per use; both names on one API is an error.
         */
        private boolean renameDownstreamAuth(ObjectNode document, Location location) {
            JsonNode apis = document.path("apis");
            for (int i = 0; i < apis.size(); i++) {
                if (!(apis.get(i) instanceof ObjectNode api) || !api.has("downstreamAuth")) {
                    continue;
                }
                Location at = location.withPointer("/apis/" + i + "/downstreamAuth");
                if (api.has("upstreamAuth")) {
                    diagnostics.error(Codes.DEFINITION_INVALID, "API " + api.path("id").asText() + " has both upstreamAuth and"
                            + " downstreamAuth; keep upstreamAuth (downstreamAuth is its name before 0.4.0)", at);
                    return false;
                }
                diagnostics.warning(Codes.DEPRECATED_KEY, "downstreamAuth is deprecated: rename it to upstreamAuth"
                        + " (the same settings; the old name is read until a later release)", at);
                ObjectNode renamed = api.objectNode();
                api.fields().forEachRemaining(f -> renamed.set(f.getKey().equals("downstreamAuth") ? "upstreamAuth" : f.getKey(), f.getValue()));
                api.removeAll();
                api.setAll(renamed);
            }
            return true;
        }

        private void normalizeApi(ObjectNode document) {
            JsonNode api = document.remove("api");
            if (api != null) {
                document.putArray("apis").add(api);
            }
        }

        private boolean checkUniqueApiIds(ObjectNode document, Location location) {
            Set<String> seen = new HashSet<>();
            JsonNode apis = document.path("apis");
            boolean ok = true;
            for (int i = 0; i < apis.size(); i++) {
                String id = apis.get(i).path("id").asText();
                if (!id.isEmpty() && !seen.add(id)) {
                    diagnostics.error(Codes.DUPLICATE_KEY, "API id '" + id + "' is used twice", location.withPointer("/apis/" + i + "/id"));
                    ok = false;
                }
            }
            return ok;
        }

        private void resolveNaming(ObjectNode document, Path file, Path root, Location location) {
            JsonNode naming = document.get("naming");
            if (naming == null || !naming.has("extends")) {
                return;
            }
            ObjectNode own = ((ObjectNode) naming).deepCopy();
            String ref = own.remove("extends").asText();
            Optional<Path> policyFile = reference(ref, file, root, location.withPointer("/naming/extends"));
            policyFile.flatMap(this::namingPolicy).ifPresent(policy ->
                    document.set("naming", new Merger(diagnostics, location).merge(policy, own)));
        }

        /**
         * {@code descriptions.include}: the notices of the shared sets come first, in order (a later set's
         * notice replaces an earlier one with the same id); a notice of this definition with the same id
         * replaces the shared one in place, others follow. Returns notice id → reference for the included ones.
         */
        private Map<String, String> resolveNotices(ObjectNode document, Path file, Path root, Location location) {
            JsonNode descriptions = document.get("descriptions");
            if (descriptions == null || !descriptions.has("include")) {
                return Map.of();
            }
            JsonNode include = ((ObjectNode) descriptions).remove("include");
            List<String> refs = new ArrayList<>();
            if (include.isTextual()) {
                refs.add(include.asText());
            } else {
                include.forEach(r -> refs.add(r.asText()));
            }
            Map<String, JsonNode> shared = new LinkedHashMap<>();
            Map<String, String> sources = new LinkedHashMap<>();
            for (int i = 0; i < refs.size(); i++) {
                String ref = refs.get(i);
                Location at = location.withPointer(include.isTextual() ? "/descriptions/include" : "/descriptions/include/" + i);
                Optional<Path> setFile = reference(ref, file, root, at);
                if (setFile.isEmpty()) {
                    continue;
                }
                Location setLocation = location(setFile.get());
                if (!enter(setFile.get(), setLocation)) {
                    continue;
                }
                try {
                    readValidated(setFile.get(), SchemaKind.NOTICES, Codes.NOTICES_INVALID, setLocation).ifPresent(set ->
                            set.path("notices").forEach(n -> {
                                shared.remove(n.get("id").asText());
                                shared.put(n.get("id").asText(), n);
                                sources.put(n.get("id").asText(), ref);
                            }));
                } finally {
                    stack.pop();
                }
            }
            ArrayNode merged = JsonNodeFactory.instance.arrayNode();
            Map<String, JsonNode> own = new LinkedHashMap<>();
            List<JsonNode> unnamed = new ArrayList<>();
            descriptions.path("notices").forEach(n -> {
                if (n.hasNonNull("id")) {
                    own.put(n.get("id").asText(), n);
                } else {
                    unnamed.add(n);
                }
            });
            shared.forEach((id, n) -> {
                if (own.containsKey(id)) {
                    merged.add(own.remove(id));
                    sources.remove(id);
                } else {
                    merged.add(n.deepCopy());
                }
            });
            own.values().forEach(merged::add);
            unnamed.forEach(merged::add);
            ((ObjectNode) descriptions).set("notices", merged);
            return sources;
        }

        private Optional<ObjectNode> namingPolicy(Path file) {
            Location location = location(file);
            if (!enter(file, location)) {
                return Optional.empty();
            }
            try {
                Optional<ObjectNode> policy = readValidated(file, SchemaKind.NAMING_POLICY, Codes.NAMING_POLICY_INVALID, location);
                if (policy.isEmpty() || !policy.get().has("extends")) {
                    return policy;
                }
                ObjectNode own = policy.get();
                String ref = own.remove("extends").asText();
                return reference(ref, file, file.getParent(), location.withPointer("/extends"))
                        .flatMap(this::namingPolicy)
                        .map(parent -> (ObjectNode) new Merger(diagnostics, location).merge(parent, own));
            } finally {
                stack.pop();
            }
        }

        private List<SourcedRule> collectRules(JsonNode overrides, Path file, Path root, Location location) {
            List<SourcedRule> rules = new ArrayList<>();
            JsonNode include = overrides.path("include");
            List<String> patterns = new ArrayList<>();
            if (include.isTextual()) {
                patterns.add(include.asText());
            } else if (include.isArray()) {
                include.forEach(p -> patterns.add(p.asText()));
            }
            for (int i = 0; i < patterns.size(); i++) {
                String pattern = patterns.get(i);
                Location includeLocation = location.withPointer(include.isArray() ? "/overrides/include/" + i : "/overrides/include");
                for (Path ruleFile : includedFiles(pattern, file, root, includeLocation)) {
                    Location ruleLocation = location(ruleFile);
                    readValidated(ruleFile, SchemaKind.RULES, Codes.RULES_INVALID, ruleLocation).ifPresent(doc -> {
                        JsonNode list = doc.path("rules");
                        for (int r = 0; r < list.size(); r++) {
                            rules.add(new SourcedRule((ObjectNode) list.get(r), ruleLocation.withPointer("/rules/" + r)));
                        }
                    });
                }
            }
            JsonNode inline = overrides.path("rules");
            for (int r = 0; r < inline.size(); r++) {
                rules.add(new SourcedRule((ObjectNode) inline.get(r), location.withPointer("/overrides/rules/" + r)));
            }
            return rules;
        }

        private List<Path> includedFiles(String pattern, Path file, Path root, Location location) {
            if (ProjectPaths.isRemote(pattern) || Workspace.isReference(pattern)) {
                return reference(pattern, file, root, location).map(List::of).orElse(List.of());
            }
            Path base = file.getParent();
            if (!checkInside(base.resolve(pattern).normalize(), root, pattern, location)) {
                return List.of();
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> matches = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> matcher.matches(base.relativize(p)))
                        .filter(p -> p.normalize().startsWith(root))
                        .sorted((a, b) -> slash(base.relativize(a)).compareTo(slash(base.relativize(b))))
                        .forEach(matches::add);
            } catch (IOException e) {
                diagnostics.error(Codes.FILE_UNREADABLE, "Cannot list files for '" + pattern + "': " + e.getMessage(), location);
            }
            if (matches.isEmpty()) {
                diagnostics.warning(Codes.INCLUDE_MATCHES_NOTHING, "'" + pattern + "' matches no file", location);
            }
            return matches;
        }

        private void resolveContracts(ObjectNode document, Path file, Path root, Location location) {
            JsonNode apis = document.path("apis");
            for (int i = 0; i < apis.size(); i++) {
                JsonNode contracts = apis.get(i).path("contracts");
                for (int j = 0; j < contracts.size(); j++) {
                    ObjectNode contract = (ObjectNode) contracts.get(j);
                    String pointer = "/apis/" + i + "/contracts/" + j;
                    if (contract.hasNonNull("spec")) {
                        reference(contract.get("spec").asText(), file, root, location.withPointer(pointer + "/spec"))
                                .ifPresent(spec -> contract.put(RESOLVED_SPEC, spec.toString()));
                    }
                    JsonNode overlays = contract.path("overlays");
                    if (overlays.isArray()) {
                        ArrayNode resolved = JsonNodeFactory.instance.arrayNode();
                        for (int k = 0; k < overlays.size(); k++) {
                            reference(overlays.get(k).asText(), file, root, location.withPointer(pointer + "/overlays/" + k))
                                    .ifPresent(o -> resolved.add(o.toString()));
                        }
                        contract.set(RESOLVED_OVERLAYS, resolved);
                    }
                }
            }
        }

        /** Replaces every {@code {"$ref": "<file>"}} object with the referenced file's content. */
        private JsonNode expandReferences(JsonNode node, Path file, Path root, String pointer) {
            if (node.isObject()) {
                ObjectNode object = (ObjectNode) node;
                JsonNode ref = object.get("$ref");
                if (object.size() == 1 && ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                    Location location = location(file).withPointer(pointer + "/$ref");
                    Optional<Path> target = reference(ref.asText(), file, root, location);
                    if (target.isEmpty() || !enter(target.get(), location)) {
                        return object;
                    }
                    try {
                        Path targetRoot = Workspace.isReference(ref.asText()) ? target.get().getParent() : root;
                        return Documents.read(target.get(), diagnostics, location(target.get()))
                                .map(content -> expandReferences(content, target.get(), targetRoot, ""))
                                .orElse(object);
                    } finally {
                        stack.pop();
                    }
                }
                List<String> names = new ArrayList<>();
                object.fieldNames().forEachRemaining(names::add);
                for (String name : names) {
                    object.set(name, expandReferences(object.get(name), file, root, pointer + "/" + Merger.escape(name)));
                }
                return object;
            }
            if (node.isArray()) {
                ArrayNode array = (ArrayNode) node;
                for (int i = 0; i < array.size(); i++) {
                    array.set(i, expandReferences(array.get(i), file, root, pointer + "/" + i));
                }
            }
            return node;
        }

        // ---------------------------------------------------------------- files and paths

        /**
         * Resolves a reference written in {@code from}: a relative path (must stay inside {@code root}),
         * a workspace reference, or — never allowed here — a remote URL.
         */
        private Optional<Path> reference(String ref, Path from, Path root, Location location) {
            if (Workspace.isReference(ref)) {
                Optional<Path> target = workspace.resolve(ref);
                if (target.isEmpty()) {
                    diagnostics.error(Codes.WORKSPACE_REFERENCE_UNRESOLVED,
                            "Workspace reference '" + ref + "' is not mapped (use --workspace)", location);
                    return Optional.empty();
                }
                if (!Files.isRegularFile(target.get())) {
                    diagnostics.error(Codes.FILE_NOT_FOUND, "Workspace reference '" + ref + "' points to a missing file", location);
                    return Optional.empty();
                }
                files.add(target.get());
                return target;
            }
            Optional<Path> resolved = ProjectPaths.resolveLocal(ref, from, root, diagnostics, location);
            resolved.ifPresent(files::add);
            return resolved;
        }

        private boolean checkInside(Path resolved, Path root, String ref, Location location) {
            if (!resolved.startsWith(root)) {
                diagnostics.error(Codes.PATH_OUTSIDE_ROOT, "Path '" + ref + "' resolves outside the project root", location);
                return false;
            }
            return true;
        }

        private Optional<ObjectNode> readValidated(Path file, SchemaKind kind, String code, Location location) {
            files.add(file);
            Optional<JsonNode> node = Documents.read(file, diagnostics, location);
            if (node.isEmpty()) {
                return Optional.empty();
            }
            if (!node.get().isObject()) {
                diagnostics.error(code, "Expected a map at the top level", location);
                return Optional.empty();
            }
            List<Schemas.Problem> problems = schemas.problems(kind, node.get());
            problems.forEach(p -> diagnostics.error(code, p.message(), location.withPointer(p.pointer())));
            return problems.isEmpty() ? Optional.of((ObjectNode) node.get()) : Optional.empty();
        }

        /** Pushes a file onto the reference stack; reports a cycle and returns false if it's already there. */
        private boolean enter(Path file, Location location) {
            if (stack.contains(file)) {
                List<String> chain = new ArrayList<>();
                stack.descendingIterator().forEachRemaining(p -> chain.add(display(p)));
                chain.add(display(file));
                diagnostics.error(Codes.REFERENCE_CYCLE, "Reference cycle: " + String.join(" → ", chain), location);
                return false;
            }
            stack.push(file);
            return true;
        }

        private Location location(Path file) {
            return Location.of(display(file));
        }

        private String display(Path file) {
            return slash(primaryRoot.relativize(file));
        }
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }
}
