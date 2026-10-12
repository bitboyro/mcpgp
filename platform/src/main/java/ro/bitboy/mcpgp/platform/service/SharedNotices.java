package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared notices on many servers at once (spec/platform-api.md §2.3): adds
 * {@code mcpgp://<workspace>/notices/<name>@<version>} to the {@code descriptions.include} of each
 * server, or moves a server that already includes the item to that version. Each changed server gets
 * its next version, like any edit; servers whose definition wouldn't compile keep theirs.
 */
public final class SharedNotices {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Store store;
    private final Library library;
    private final Edits edits;
    private final String workspace;

    public SharedNotices(Store store, Library library, Edits edits, String workspace) {
        this.store = store;
        this.library = library;
        this.edits = edits;
        this.workspace = workspace;
    }

    /**
     * @param servers the servers to change; null for every server that isn't archived
     * @param version the item's version to use; null for its latest
     * @return one line per server: {@code server}, {@code status} (updated, unchanged, inherited,
     *         failed), and {@code version}, {@code from} or {@code error}
     */
    public List<Map<String, Object>> apply(String name, List<String> servers, Integer version, String message, Access.Principal who) {
        Model.LibraryItem item = library.get("notices", name);
        int number = version == null ? item.latest().number() : version;
        if (item.versions().stream().noneMatch(v -> v.number() == number)) {
            throw ApiException.validation("version: notices/" + name + " has versions 1 to " + item.latest().number());
        }
        String reference = "mcpgp://" + workspace + "/notices/" + name + "@" + number;
        Pattern same = Pattern.compile("^mcpgp://" + Pattern.quote(workspace) + "/notices/" + Pattern.quote(name) + "(?:@\\w+)?$");

        List<Model.Server> targets = new ArrayList<>();
        if (servers == null) {
            store.servers().stream().filter(s -> !s.archived() && s.latest() != null).forEach(targets::add);
        } else {
            for (String id : new LinkedHashSet<>(servers)) {
                targets.add(store.server(id).filter(s -> s.latest() != null).orElseThrow(() -> ApiException.notFound("server " + id)));
            }
        }
        Set<String> chosen = new LinkedHashSet<>();
        targets.forEach(s -> chosen.add(s.id()));

        List<Map<String, Object>> results = new ArrayList<>();
        for (Model.Server server : targets) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("server", server.id());
            results.add(line);
            if (server.parent() != null && chosen.contains(server.parent())) {
                // a variant gets the notices of the server it extends
                line.put("status", "inherited");
                line.put("from", server.parent());
                continue;
            }
            JsonNode include;
            try {
                Model.Version files = store.version(server.latest().digest()).orElseThrow();
                JsonNode doc = YAML.readTree(store.blob(files.files().get(files.root())));
                include = doc == null ? JsonNodeFactory.instance.missingNode() : doc.at("/descriptions/include");
            } catch (IOException e) {
                line.put("status", "failed");
                line.put("error", "the definition can't be read");
                continue;
            }
            List<String> refs = new ArrayList<>();
            if (include.isTextual()) {
                refs.add(include.asText());
            } else {
                include.forEach(r -> refs.add(r.asText()));
            }
            String previous = refs.stream().filter(r -> same.matcher(r).matches()).findFirst().orElse(null);
            if (reference.equals(previous)) {
                line.put("status", "unchanged");
                continue;
            }
            List<String> next = new ArrayList<>();
            boolean replaced = false;
            for (String ref : refs) {
                if (same.matcher(ref).matches()) {
                    if (!replaced) {
                        next.add(reference);
                        replaced = true;
                    }
                } else {
                    next.add(ref);
                }
            }
            if (!replaced) {
                next.add(reference);
            }
            ArrayNode value = JsonNodeFactory.instance.arrayNode();
            next.forEach(value::add);
            try {
                Model.Server saved = edits.save(server.id(), server.latest().number(),
                        List.of(new YamlEdit.Change("set", "/descriptions/include", value)),
                        message != null && !message.isBlank() ? message : (previous == null ? "Use " : "Move to ") + "shared notices " + name + "@" + number,
                        who);
                line.put("status", "updated");
                line.put("version", saved.latest().number());
                if (previous != null) {
                    line.put("from", previous);
                }
            } catch (ApiException e) {
                line.put("status", "failed");
                line.put("error", e.getMessage());
            }
        }
        store.append(new Model.Activity(Instant.now().toString(), who.identity().name(), who.identity().kind(), "library.apply",
                "notices/" + name + "@" + number, "ok",
                Map.of("updated", results.stream().filter(r -> "updated".equals(r.get("status"))).map(r -> r.get("server")).toList())));
        return results;
    }
}
