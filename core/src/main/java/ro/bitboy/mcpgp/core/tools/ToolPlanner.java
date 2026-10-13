package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Contract;
import ro.bitboy.mcpgp.core.openapi.ApiModel.MediaType;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.ContractKey;
import ro.bitboy.mcpgp.core.resolve.ResolvedDefinition.SourcedRule;
import ro.bitboy.mcpgp.core.tools.ToolPlan.Annotations;
import ro.bitboy.mcpgp.core.tools.ToolPlan.Provenance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which operations become tools and how they look: selection, rules, content-type binding,
 * the naming pipeline (naming.md) and the lock file. Schemas are built in the next stage.
 */
public final class ToolPlanner {

    public static final int DESCRIPTION_LIMIT = 1024;
    private static final Map<String, String> DEFAULT_SUFFIXES = Map.of(
            "multipart/form-data", "multipart",
            "application/x-www-form-urlencoded", "form",
            "application/octet-stream", "binary");

    private final Diagnostics diagnostics;
    private final boolean frozenLock;

    public ToolPlanner(Diagnostics diagnostics, boolean frozenLock) {
        this.diagnostics = diagnostics;
        this.frozenLock = frozenLock;
    }

    public List<ToolPlan> plan(ResolvedDefinition definition, Map<ContractKey, Contract> contracts, NamesLock lock) {
        JsonNode document = definition.document();
        Location root = Location.of(definition.root().relativize(definition.file()).toString().replace('\\', '/'));
        Naming naming = new Naming(document.path("naming"));
        boolean severalApis = document.path("apis").size() > 1;
        boolean confirmDestructive = document.path("safety").path("confirmDestructive").asBoolean(true);
        Set<SourcedRule> usedRules = new HashSet<>();
        List<ToolPlan> plans = new ArrayList<>();

        for (JsonNode api : document.path("apis")) {
            String apiId = api.path("id").asText();
            String prefix = api.path("toolPrefix").asText(apiId);
            List<String> versions = new ArrayList<>();
            String defaultVersion = null;
            for (JsonNode c : api.path("contracts")) {
                String version = c.path("version").asText();
                if (contracts.containsKey(new ContractKey(apiId, version))) {
                    versions.add(version);
                    if (c.path("default").asBoolean(false)) {
                        defaultVersion = version;
                    }
                }
            }
            if (versions.isEmpty()) {
                continue;
            }
            if (defaultVersion == null) {
                defaultVersion = versions.get(versions.size() - 1);
            }

            // operations by key, default contract's order first
            Map<String, Map<String, Operation>> byKey = new LinkedHashMap<>();
            List<String> order = new ArrayList<>(versions);
            order.remove(defaultVersion);
            order.add(0, defaultVersion);
            for (String version : order) {
                for (Operation op : contracts.get(new ContractKey(apiId, version)).operations()) {
                    byKey.computeIfAbsent(op.key(), k -> new LinkedHashMap<>()).put(version, op);
                }
            }

            JsonNode select = api.path("select");
            for (Map.Entry<String, Map<String, Operation>> entry : byKey.entrySet()) {
                Map<String, Operation> variants = new LinkedHashMap<>();
                String reason = null;
                for (String version : versions) {
                    Operation op = entry.getValue().get(version);
                    if (op == null) {
                        continue;
                    }
                    String excluded = selectionReason(select, apiId, op, version);
                    if (excluded == null) {
                        variants.put(version, op);
                    } else if (reason == null) {
                        reason = excluded;
                    }
                }
                if (variants.isEmpty()) {
                    diagnostics.info(Codes.OPERATION_EXCLUDED, entry.getKey() + " excluded: " + reason, root);
                    continue;
                }
                Operation base = variants.containsKey(defaultVersion)
                        ? variants.get(defaultVersion)
                        : variants.get(new ArrayList<>(variants.keySet()).get(variants.size() - 1));
                planOperation(definition, api, apiId, prefix, severalApis, naming, defaultVersion, base, variants,
                        confirmDestructive, usedRules, plans, root);
            }
        }

        for (SourcedRule rule : definition.rules()) {
            // rules for pack, built-in and custom tools are checked when those tools are written
            JsonNode match = rule.rule().path("match");
            if (!usedRules.contains(rule) && !match.has("kind") && !match.has("tool")) {
                diagnostics.warning(Codes.RULE_MATCHES_NOTHING, "Rule matches no tool", rule.location());
            }
        }
        List<ToolPlan> named = applyLockAndCollisions(plans, naming, lock, root);
        named.sort(Comparator.comparing(ToolPlan::name));
        return List.copyOf(named);
    }

    // ---------------------------------------------------------------------- selection

    /** Why an operation is not selected in one contract, or null when it is. */
    private static String selectionReason(JsonNode select, String api, Operation op, String version) {
        Exclusion exclusion = exclusion(select, api, op, version);
        return exclusion == null ? null : exclusion.reason();
    }

    /**
     * Why {@code select} leaves an operation out of a contract, or null when it doesn't.
     *
     * @param kind     {@code access}, {@code include}, {@code exclude} or {@code deprecated}
     * @param index    for {@code exclude}: the first matching entry (0 when {@code exclude} is one selector)
     * @param selector for {@code exclude}: that entry
     */
    public record Exclusion(String kind, String reason, Integer index, JsonNode selector) {
    }

    public static Exclusion exclusion(JsonNode select, String api, Operation op, String version) {
        String access = select.path("access").asText("read-write");
        boolean read = op.method().equals("GET") || op.method().equals("HEAD");
        if (access.equals("read-only") && !read) {
            return new Exclusion("access", "access: read-only", null, null);
        }
        if (access.equals("read-write") && op.method().equals("DELETE")) {
            return new Exclusion("access", "access: read-write", null, null);
        }
        List<String> versions = List.of(version);
        JsonNode include = select.get("include");
        if (include != null && list(include).stream().noneMatch(s -> Selectors.matches(s, api, op, versions))) {
            return new Exclusion("include", "not included", null, null);
        }
        JsonNode exclude = select.get("exclude");
        if (exclude != null) {
            List<JsonNode> entries = list(exclude);
            for (int i = 0; i < entries.size(); i++) {
                if (Selectors.matches(entries.get(i), api, op, versions)) {
                    return new Exclusion("exclude", "excluded by select.exclude", i, entries.get(i));
                }
            }
        }
        if (op.deprecated() && select.path("deprecated").asText("hide").equals("hide")) {
            return new Exclusion("deprecated", "deprecated", null, null);
        }
        return null;
    }

    /** The spec's description with the description rules applied in order, and the steps for provenance. */
    private static String describe(String spec, String pointer, List<Map.Entry<SourcedRule, Map<String, String>>> rules, List<Provenance> provenance) {
        String description = spec;
        provenance.add(new Provenance("spec", description, null, pointer, null, null));
        for (Map.Entry<SourcedRule, Map<String, String>> entry : rules) {
            ObjectNode rule = entry.getKey().rule();
            Location at = entry.getKey().location();
            description = TextOps.apply(rule.get("description"), description, entry.getValue());
            provenance.add(new Provenance("rule", description, at.file(), at.pointer(), TextOps.kind(rule.get("description")), null));
        }
        return description;
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(result::add);
        } else {
            result.add(node);
        }
        return result;
    }

    // ---------------------------------------------------------------------- one operation

    private void planOperation(ResolvedDefinition definition, JsonNode api, String apiId, String prefix, boolean severalApis,
                               Naming naming, String defaultVersion, Operation base, Map<String, Operation> variants,
                               boolean confirmDestructive, Set<SourcedRule> usedRules, List<ToolPlan> plans, Location root) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("operationId", base.operationId() == null ? "" : base.operationId());
        variables.put("summary", base.summary() == null ? "" : base.summary());
        variables.put("api", apiId);
        variables.put("contract", defaultVersion);
        variables.put("method", base.method());
        variables.put("path", base.path());

        // naming steps 1-4
        List<Naming.Step> steps = new ArrayList<>();
        String name = naming.base(base, steps);
        List<Provenance> nameProvenance = new ArrayList<>();
        steps.forEach(s -> nameProvenance.add(Provenance.of(s.step(), s.value())));

        String title = base.summary() != null ? base.summary() : humanize(name);
        String specDescription = base.description() != null ? base.description() : base.summary() == null ? "" : base.summary();
        String descriptionPointer = base.pointer() + (base.description() != null ? "/description" : "/summary");
        List<Provenance> titleProvenance = new ArrayList<>(List.of(new Provenance("spec", title, null, base.pointer() + "/summary", null, null)));
        // description rules run after the loop, so a description that is too long can lose spec text instead of what rules add
        List<Map.Entry<SourcedRule, Map<String, String>>> descriptionRules = new ArrayList<>();

        Annotations annotations = defaults(base.method());
        boolean hidden = false;
        boolean sensitive = false;
        Boolean confirm = null;
        String expose = "tool";
        String contentType = null;
        List<String> accept = null;
        Map<String, String> params = new LinkedHashMap<>();
        Map<String, ObjectNode> parts = new LinkedHashMap<>();

        for (SourcedRule sourced : definition.rules()) {
            ObjectNode rule = sourced.rule();
            if (!Selectors.matches(rule.get("match"), apiId, base, variants.keySet())) {
                continue;
            }
            usedRules.add(sourced);
            Location at = sourced.location();
            variables.put("name", name);
            if (rule.has("name")) {
                name = TextOps.apply(rule.get("name"), name, variables);
                nameProvenance.add(new Provenance("rule", name, at.file(), at.pointer(), TextOps.kind(rule.get("name")), null));
            }
            if (rule.has("title")) {
                title = TextOps.apply(rule.get("title"), title, variables);
                titleProvenance.add(new Provenance("rule", title, at.file(), at.pointer(), TextOps.kind(rule.get("title")), null));
            }
            if (rule.has("description")) {
                descriptionRules.add(Map.entry(sourced, new LinkedHashMap<>(variables)));
            }
            rule.path("params").fields().forEachRemaining(p -> {
                if (p.getValue().has("description")) {
                    params.put(p.getKey(), TextOps.apply(p.getValue().get("description"), params.get(p.getKey()), variables));
                }
            });
            rule.path("parts").fields().forEachRemaining(p -> {
                ObjectNode merged = parts.computeIfAbsent(p.getKey(), k -> JsonNodeFactory.instance.objectNode());
                merged.setAll((ObjectNode) p.getValue());
            });
            if (rule.has("annotations")) {
                annotations = override(annotations, rule.get("annotations"));
            }
            hidden = rule.path("hidden").asBoolean(hidden);
            sensitive = rule.path("sensitive").asBoolean(sensitive);
            if (rule.has("confirm")) {
                confirm = rule.get("confirm").asBoolean();
            }
            expose = rule.path("expose").asText(expose);
            contentType = rule.path("contentType").asText(contentType);
            if (rule.has("accept")) {
                List<String> a = new ArrayList<>();
                rule.get("accept").forEach(x -> a.add(x.asText()));
                accept = List.copyOf(a);
            }
        }
        if (!hidden && expose.equals("resource")) {
            // a resource is read, never acted on: only GET, and everything it needs must fit in its URI
            if (variants.values().stream().anyMatch(v -> !v.method().equalsIgnoreCase("GET"))) {
                diagnostics.error(Codes.RESOURCE_NOT_GET, base.key() + " is exposed as a resource, but resources are GET operations only", root);
            }
            variants.values().stream().flatMap(v -> v.parameters().stream())
                    .filter(p -> p.required() && (p.in().equals("header") || p.in().equals("cookie"))).findFirst()
                    .ifPresent(p -> diagnostics.error(Codes.RESOURCE_NEEDS_HEADER, base.key() + " needs the " + p.in() + " parameter " + p.name()
                            + ", which a resource URI can't carry; keep it a tool", root));
        }
        if (hidden) {
            diagnostics.info(Codes.OPERATION_EXCLUDED, base.key() + " excluded: hidden by a rule", root);
            return;
        }
        String deprecatedNote = base.deprecated() && api.path("select").path("deprecated").asText("hide").equals("mark") ? " (Deprecated.)" : "";
        List<Provenance> descriptionProvenance = new ArrayList<>();
        String description = describe(specDescription, descriptionPointer, descriptionRules, descriptionProvenance) + deprecatedNote;
        if (description.length() > DESCRIPTION_LIMIT) {
            // shorten the spec's text so what rules prepend or append (a disclaimer, say) stays whole
            int excess = description.length() - DESCRIPTION_LIMIT + 1;
            String shortened = specDescription.length() > excess ? specDescription.substring(0, specDescription.length() - excess).stripTrailing() + "…" : "";
            descriptionProvenance.clear();
            description = describe(shortened, descriptionPointer, descriptionRules, descriptionProvenance) + deprecatedNote;
            if (description.length() > DESCRIPTION_LIMIT) {
                description = description.substring(0, DESCRIPTION_LIMIT - 1) + "…";
            }
            diagnostics.warning(Codes.DESCRIPTION_TRUNCATED, base.key() + ": description shortened to " + DESCRIPTION_LIMIT + " characters", root);
        }
        boolean needsConfirm = confirm != null ? confirm : confirmDestructive && annotations.destructiveHint();

        String namespaced = naming.namespace(name, prefix, severalApis);
        if (!namespaced.equals(name)) {
            nameProvenance.add(Provenance.of("namespace", namespaced));
        }

        // content types: one tool per bound request media type
        List<String> media = mediaTypes(base, api.path("requestBody"), contentType, root);
        String primary = primary(media);
        for (String type : media) {
            String toolName = namespaced;
            String id = base.key();
            List<Provenance> provenance = new ArrayList<>(nameProvenance);
            if (type != null && !type.equals(primary)) {
                String suffix = suffix(type, api.path("requestBody").path("suffixes"));
                toolName = namespaced + "_" + suffix;
                id = base.key() + "#" + suffix;
                provenance.add(Provenance.of("contentTypeSuffix", toolName));
            }
            // the name it would get from its method and path, should its own clash (collisions: method-path)
            String fromMethodPath = naming.namespace(naming.methodPathBase(base), prefix, severalApis)
                    + (toolName.length() > namespaced.length() ? toolName.substring(namespaced.length()) : "");
            methodPathNames.put(id, naming.cased(fromMethodPath));
            plans.add(new ToolPlan(naming.cased(toolName), id, apiId, base.key(), base.operationId(), base.tags(), type,
                    title, description, annotations, sensitive, needsConfirm, expose, accept,
                    Map.copyOf(params), Map.copyOf(parts), Map.copyOf(variants),
                    Map.of("name", List.copyOf(provenance), "title", List.copyOf(titleProvenance),
                            "description", List.copyOf(descriptionProvenance))));
        }
    }

    private List<String> mediaTypes(Operation op, JsonNode requestBody, String bound, Location root) {
        List<String> available = new ArrayList<>();
        if (op.requestBody() != null) {
            op.requestBody().content().stream().map(MediaType::name).forEach(available::add);
        }
        List<String> result = new ArrayList<>();
        if (bound != null) {
            if (!available.contains(bound)) {
                diagnostics.error(Codes.CONTENT_TYPE_UNKNOWN, op.key() + " does not accept " + bound + " (accepts " + available + ")", root);
                return List.of();
            }
            result.add(bound);
        } else if (available.isEmpty()) {
            result.add(null);
        } else if (requestBody.path("multiple").asText("split").equals("prefer")) {
            String chosen = available.get(0);
            for (JsonNode preferred : requestBody.path("prefer")) {
                if (available.contains(preferred.asText())) {
                    chosen = preferred.asText();
                    break;
                }
            }
            result.add(chosen);
        } else {
            result.addAll(available);
        }
        return result;
    }

    /** The media type that keeps the base name: the first JSON one, otherwise the first. */
    private static String primary(List<String> media) {
        for (String m : media) {
            if (m != null && isJson(m)) {
                return m;
            }
        }
        return media.isEmpty() ? null : media.get(0);
    }

    static boolean isJson(String media) {
        String m = media.toLowerCase(Locale.ROOT);
        return m.equals("application/json") || m.endsWith("+json") || m.startsWith("application/json;");
    }

    private static String suffix(String media, JsonNode configured) {
        if (configured.has(media)) {
            return configured.get(media).asText();
        }
        String known = DEFAULT_SUFFIXES.get(media);
        if (known != null) {
            return known;
        }
        String subtype = media.contains("/") ? media.substring(media.indexOf('/') + 1) : media;
        return subtype.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }

    // ---------------------------------------------------------------------- lock and collisions

    /** Each tool's name from its method and path (by tool id), for {@code collisions: method-path}. */
    private final Map<String, String> methodPathNames = new java.util.HashMap<>();

    private List<ToolPlan> applyLockAndCollisions(List<ToolPlan> plans, Naming naming, NamesLock lock, Location root) {
        List<ToolPlan> result = new ArrayList<>();
        for (ToolPlan plan : plans) {
            String limited = naming.limit(plan.name());
            if (limited == null) {
                diagnostics.error(Codes.NAME_TOO_LONG, "Name '" + plan.name() + "' is too long for profile " + naming.profile, root);
                continue;
            }
            List<Provenance> provenance = new ArrayList<>(plan.provenance().get("name"));
            if (!limited.equals(plan.name())) {
                provenance.add(Provenance.of("limits", limited));
            }
            String finalName = limited;
            Optional<NamesLock.Entry> locked = lock.get(plan.id());
            if (locked.isPresent()) {
                if (!locked.get().name().equals(limited)) {
                    if (frozenLock) {
                        diagnostics.error(Codes.NAME_DRIFT_FROZEN, "Name of " + plan.id() + " would change from '" + locked.get().name()
                                + "' to '" + limited + "'; run 'mcpgp names accept'", Location.of(NamesLock.FILE_NAME));
                    } else {
                        diagnostics.warning(Codes.NAME_DRIFT, "Name of " + plan.id() + " would be '" + limited + "', kept '"
                                + locked.get().name() + "' from the lock file", Location.of(NamesLock.FILE_NAME));
                    }
                }
                finalName = locked.get().name();
                provenance.add(new Provenance("lock", finalName, NamesLock.FILE_NAME, null, null, locked.get().since()));
            }
            Map<String, List<Provenance>> all = new LinkedHashMap<>(plan.provenance());
            all.put("name", List.copyOf(provenance));
            result.add(rename(plan, finalName, all));
        }

        Map<String, ToolPlan> byName = new LinkedHashMap<>();
        List<ToolPlan> unique = new ArrayList<>();
        for (ToolPlan plan : result) {
            ToolPlan existing = byName.get(plan.name());
            if (existing == null) {
                byName.put(plan.name(), plan);
                unique.add(plan);
                continue;
            }
            if (naming.collisions.equals("method-path") && methodPathNames.containsKey(plan.id())) {
                String candidate = naming.limit(methodPathNames.get(plan.id()));
                for (int n = 2; candidate != null && byName.containsKey(candidate); n++) {
                    candidate = naming.limit(methodPathNames.get(plan.id()) + "_" + n);
                }
                if (candidate != null) {
                    List<Provenance> provenance = new ArrayList<>(plan.provenance().get("name"));
                    provenance.add(Provenance.of("collision", candidate));
                    Map<String, List<Provenance>> all = new LinkedHashMap<>(plan.provenance());
                    all.put("name", List.copyOf(provenance));
                    ToolPlan renamed = rename(plan, candidate, all);
                    byName.put(renamed.name(), renamed);
                    unique.add(renamed);
                    diagnostics.info(Codes.NAME_COLLISION, plan.id() + " is named '" + candidate + "' from its method and path: '"
                            + plan.name() + "' is " + existing.id() + "'s (naming.collisions: method-path)", root);
                    continue;
                }
            }
            if (naming.collisions.equals("suffix-number")) {
                int n = 2;
                while (byName.containsKey(plan.name() + "_" + n)) {
                    n++;
                }
                ToolPlan renamed = rename(plan, plan.name() + "_" + n, plan.provenance());
                byName.put(renamed.name(), renamed);
                unique.add(renamed);
            } else {
                diagnostics.error(Codes.NAME_COLLISION, "Tools " + existing.id() + " and " + plan.id() + " are both named '"
                        + plan.name() + "'; add a rule that renames one" + (methodPathNames.containsKey(plan.id())
                        ? " (e.g. " + methodPathNames.get(plan.id()) + ", from its method and path), or set naming.collisions: method-path" : ""), root);
            }
        }
        return unique;
    }

    private static ToolPlan rename(ToolPlan p, String name, Map<String, List<Provenance>> provenance) {
        return new ToolPlan(name, p.id(), p.api(), p.operationKey(), p.operationId(), p.tags(), p.mediaType(), p.title(),
                p.description(), p.annotations(), p.sensitive(), p.confirm(), p.expose(), p.accept(), p.paramDescriptions(),
                p.parts(), p.variants(), Map.copyOf(provenance));
    }

    // ---------------------------------------------------------------------- annotations

    private static Annotations defaults(String method) {
        return switch (method) {
            case "GET", "HEAD" -> new Annotations(true, false, true, true);
            case "PUT" -> new Annotations(false, false, true, true);
            case "DELETE" -> new Annotations(false, true, true, true);
            default -> new Annotations(false, false, false, true);
        };
    }

    private static Annotations override(Annotations a, JsonNode o) {
        return new Annotations(
                o.path("readOnlyHint").asBoolean(a.readOnlyHint()),
                o.path("destructiveHint").asBoolean(a.destructiveHint()),
                o.path("idempotentHint").asBoolean(a.idempotentHint()),
                o.path("openWorldHint").asBoolean(a.openWorldHint()));
    }

    private static String humanize(String snake) {
        String words = snake.replace('_', ' ').trim();
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
