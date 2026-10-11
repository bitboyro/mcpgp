package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import ro.bitboy.mcpgp.core.Compiler;
import ro.bitboy.mcpgp.core.tools.NamesLock;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.IOException;

/**
 * A version's naming (naming.md, spec/platform-api.md §2.1): the policy it extends, what the definition
 * sets itself, the effective policy after merging, and the lock file's entries. The Naming section of
 * Tools & rules shows it next to each tool's name steps from the manifest.
 */
public final class NamingView {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private NamingView() {
    }

    /** @param result a compile of {@code version} that succeeded */
    public static ObjectNode of(Compiler.Result result, Model.Version version, Store store) {
        ObjectNode answer = NODES.objectNode();
        JsonNode own = read(store, version, version.root()).path("naming");
        answer.put("extends", own.path("extends").isTextual() ? own.path("extends").asText() : null);
        ObjectNode ownCopy = own.isObject() ? ((ObjectNode) own).deepCopy() : NODES.objectNode();
        ownCopy.remove("extends");
        answer.set("own", ownCopy);
        JsonNode effective = result.definition().orElseThrow().document().path("naming");
        answer.set("effective", effective.isObject() ? effective.deepCopy() : NODES.objectNode());

        ArrayNode tools = answer.putArray("tools");
        result.tools().forEach(t -> tools.addObject().put("name", t.name()).put("id", t.id()));

        ObjectNode lock = answer.putObject("lock");
        String lockPath = lockPath(version);
        lock.put("present", lockPath != null);
        ArrayNode entries = lock.putArray("entries");
        if (lockPath != null) {
            for (JsonNode tool : read(store, version, lockPath).path("tools")) {
                ObjectNode entry = entries.addObject().put("id", tool.path("id").asText()).put("name", tool.path("name").asText());
                if (tool.has("since")) {
                    entry.put("since", tool.path("since").asText());
                }
            }
        }
        return answer;
    }

    /** The lock file next to the root definition, if the version has one. */
    private static String lockPath(Model.Version version) {
        int slash = version.root().lastIndexOf('/');
        String path = (slash < 0 ? "" : version.root().substring(0, slash + 1)) + NamesLock.FILE_NAME;
        return version.files().containsKey(path) ? path : null;
    }

    private static JsonNode read(Store store, Model.Version version, String path) {
        String blob = version.files().get(path);
        if (blob == null) {
            return NODES.missingNode();
        }
        try {
            JsonNode node = YAML.readTree(store.blob(blob));
            return node == null ? NODES.missingNode() : node;
        } catch (IOException e) {
            return NODES.missingNode();   // the compile already reported it
        }
    }
}
