package ro.bitboy.mcpgp.runtime.config;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

/** Immutable deployment/API/instance permission snapshot, shared by catalogs and execution. */
public final class ToolPolicy {
    private record Rule(String source, List<Pattern> allow, List<Pattern> deny) {
        boolean permits(String name) {
            return (allow == null || allow.stream().anyMatch(p -> p.matcher(name).matches()))
                    && deny.stream().noneMatch(p -> p.matcher(name).matches());
        }
    }

    private final Manifest manifest;
    private final Map<String, List<RuntimeConfig.Instance>> instances;
    private final Rule deployment;
    private final Rule organization;
    private final JsonNode document;
    private final Map<String, Rule> apis = new LinkedHashMap<>();
    private final Map<String, Map<String, Rule>> targets = new LinkedHashMap<>();
    private final boolean namespaced;

    public ToolPolicy(Manifest manifest, RuntimeConfig config) {
        this(manifest, config.document(), config.instances());
    }

    private ToolPolicy(Manifest manifest, JsonNode document, Map<String, List<RuntimeConfig.Instance>> instances) {
        this.manifest = manifest;
        this.document = document;
        this.organization = rule(document.path("_organization").path("tools"), "organization/tools");
        this.instances = instances;
        this.namespaced = manifest.multiInstance() && manifest.root().at("/instances/mixedContracts").asText().equals("namespaced");
        this.deployment = rule(document.path("tools"), "/tools");
        boolean multiApi = manifest.apis().size() > 1;
        manifest.apis().keySet().forEach(api -> {
            JsonNode scope = multiApi ? document.path("apis").path(api) : document;
            String at = multiApi ? "/apis/" + api : "";
            if (multiApi) {
                apis.put(api, rule(scope.path("tools"), at + "/tools"));
            }
            Map<String, Rule> byId = new LinkedHashMap<>();
            if (manifest.multiInstance()) {
                int index = 0;
                for (JsonNode item : scope.path("instances")) {
                    byId.put(item.path("id").asText(), rule(item.path("tools"), at + "/instances/" + index++ + "/tools"));
                }
            } else {
                byId.put("default", rule(scope.path("instance").path("tools"), at + "/instance/tools"));
            }
            targets.put(api, Map.copyOf(byId));
        });
    }

    private static Rule rule(JsonNode node, String source) {
        return new Rule(source, node.has("allow") ? patterns(node.path("allow")) : null, patterns(node.path("deny")));
    }

    private static List<Pattern> patterns(JsonNode list) {
        List<Pattern> result = new ArrayList<>();
        list.forEach(n -> result.add(glob(n.asText())));
        return List.copyOf(result);
    }

    private static Pattern glob(String value) {
        return Pattern.compile(java.util.Arrays.stream(value.split("\\*", -1))
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*")));
    }

    /** Final advertised name, including mixed-contract instance prefixes. */
    public String name(JsonNode tool, RuntimeConfig.Instance instance) {
        String original = tool.path("name").asText();
        if (!namespaced || !tool.path("kind").asText().equals("api") || tool.has("x-mcpgp-original")) {
            return original;
        }
        return instance.id().toLowerCase(java.util.Locale.ROOT).replace('-', '_') + "__" + original;
    }

    private boolean controlled(JsonNode tool) {
        return !Set.of("builtin", "pack").contains(tool.path("kind").asText());
    }

    /** First contributing denial, for operator explanations. Built-ins enforce the tools they reach. */
    public Optional<String> deniedBy(JsonNode tool, RuntimeConfig.Instance instance) {
        if (!controlled(tool)) {
            return Optional.empty();
        }
        String name = instance == null ? tool.path("name").asText() : name(tool, instance);
        if (!organization.permits(name) || !permits(document.path("_organization").path("tools"), tool, name)) {
            return Optional.of("organization/tools");
        }
        if (!deployment.permits(name) || !permits(document.path("tools"), tool, name)) {
            return Optional.of(deployment.source());
        }
        String api = tool.path("api").asText("");
        Rule apiRule = apis.get(api);
        if (apiRule != null && (!apiRule.permits(name) || !permits(document.path("apis").path(api).path("tools"), tool, name))) {
            return Optional.of(apiRule.source());
        }
        if (instance != null) {
            Rule target = targets.getOrDefault(api, Map.of()).get(instance.id());
            JsonNode local = document;
            if (manifest.apis().size() > 1) { local = local.path("apis").path(api); }
            if (manifest.multiInstance()) {
                for (JsonNode entry : local.path("instances")) { if (entry.path("id").asText().equals(instance.id())) { local = entry; break; } }
            } else { local = local.path("instance"); }
            if (target != null && (!target.permits(name) || !permits(local.path("tools"), tool, name))) {
                return Optional.of(target.source());
            }
        }
        return Optional.empty();
    }

    public static boolean permits(JsonNode policy, JsonNode tool, String name) {
        return rule(policy, "").permits(name) && (!policy.path("readOnly").asBoolean(false)
                || tool.at("/annotations/readOnlyHint").asBoolean(false));
    }

    public boolean allows(JsonNode tool, RuntimeConfig.Instance instance) {
        return deniedBy(tool, instance).isEmpty();
    }

    public List<RuntimeConfig.Instance> allowedInstances(JsonNode tool) {
        return instances.getOrDefault(tool.path("api").asText(), List.of()).stream()
                .filter(i -> allows(tool, i)).toList();
    }

    public boolean visible(JsonNode tool) {
        return tool.has("api") ? !allowedInstances(tool).isEmpty() : allows(tool, null);
    }

    /** Validate names against shipped capabilities, never against a previously filtered catalog. */
    public static void validate(Manifest manifest, JsonNode document, Map<String, List<RuntimeConfig.Instance>> instances,
                                List<String> problems, List<String> warnings) {
        ToolPolicy policy = new ToolPolicy(manifest, document, instances);
        List<JsonNode> capabilities = new ArrayList<>(manifest.tools().values());
        manifest.root().path("resources").path("items").forEach(capabilities::add);
        Set<String> all = new LinkedHashSet<>();
        Map<String, Set<String>> byApi = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> byInstance = new LinkedHashMap<>();
        for (JsonNode tool : capabilities) {
            if (!policy.controlled(tool)) {
                continue;
            }
            String api = tool.path("api").asText("");
            if (api.isEmpty()) {
                all.add(tool.path("name").asText());
            } else {
                for (RuntimeConfig.Instance instance : instances.getOrDefault(api, List.of())) {
                    String name = policy.name(tool, instance);
                    all.add(name);
                    byApi.computeIfAbsent(api, k -> new LinkedHashSet<>()).add(name);
                    byInstance.computeIfAbsent(api, k -> new LinkedHashMap<>())
                            .computeIfAbsent(instance.id(), k -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        check(document.path("tools"), "/tools", all, problems, warnings);
        check(document.path("_organization").path("tools"), "organization/tools", all, problems, warnings);
        JsonNode identities = document.path("_identities");
        check(identities.path("default").path("tools"), "identity/default/tools", all, problems, warnings);
        for (String group : List.of("subjects", "scopes")) {
            identities.path(group).properties().forEach(e -> check(e.getValue().path("tools"), "identity/" + group + "/" + e.getKey() + "/tools", all, problems, warnings));
        }
        boolean multiApi = manifest.apis().size() > 1;
        for (String api : manifest.apis().keySet()) {
            JsonNode scope = multiApi ? document.path("apis").path(api) : document;
            String at = multiApi ? "/apis/" + api : "";
            if (multiApi) {
                check(scope.path("tools"), at + "/tools", byApi.getOrDefault(api, Set.of()), problems, warnings);
            }
            if (manifest.multiInstance()) {
                int index = 0;
                for (JsonNode item : scope.path("instances")) {
                    check(item.path("tools"), at + "/instances/" + index++ + "/tools",
                            byInstance.getOrDefault(api, Map.of()).getOrDefault(item.path("id").asText(), Set.of()), problems, warnings);
                }
            } else {
                check(scope.path("instance").path("tools"), at + "/instance/tools",
                        byApi.getOrDefault(api, Set.of()), problems, warnings);
            }
        }
    }

    private static void check(JsonNode node, String at, Set<String> names, List<String> problems, List<String> warnings) {
        for (String key : List.of("allow", "deny")) {
            for (JsonNode entry : node.path(key)) {
                String text = entry.asText();
                if (names.stream().noneMatch(n -> glob(text).matcher(n).matches())) {
                    String message = at + "/" + key + ": no shipped tool matches " + text;
                    (text.contains("*") ? warnings : problems).add(message);
                }
            }
        }
    }
}
