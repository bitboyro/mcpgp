package ro.bitboy.mcpgp.runtime.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The manifest a server runs (manifest.md). Normally the copy embedded in the jar at
 * {@value #CLASSPATH_LOCATION}; read-only views over the JSON tree.
 */
public final class Manifest {

    public static final String CLASSPATH_LOCATION = "/mcpgp/manifest.json";
    /** Manifest major versions this runtime understands (manifest.md §10). */
    static final int SUPPORTED_MAJOR = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One API of the server: its id, title and contracts. */
    public record Api(String id, String title, String toolPrefix, Map<String, Contract> contracts, String defaultContract,
                      List<String> supportedAuth, JsonNode node) {
    }

    public record Contract(String version, String format, String basePath, boolean isDefault) {
    }

    private final JsonNode root;
    private final Map<String, Api> apis = new LinkedHashMap<>();
    private final Map<String, JsonNode> tools = new LinkedHashMap<>();

    Manifest(JsonNode root) {
        this.root = root;
        for (JsonNode api : root.path("apis")) {
            Map<String, Contract> contracts = new LinkedHashMap<>();
            String defaultContract = null;
            for (JsonNode c : api.path("contracts")) {
                Contract contract = new Contract(c.path("version").asText(), c.path("format").asText(),
                        c.path("basePath").asText(""), c.path("default").asBoolean(false));
                contracts.put(contract.version(), contract);
                if (contract.isDefault()) {
                    defaultContract = contract.version();
                }
            }
            List<String> auth = new ArrayList<>();
            api.path("downstreamAuth").path("supported").forEach(a -> auth.add(a.asText()));
            String id = api.path("id").asText();
            apis.put(id, new Api(id, api.path("title").asText(id), api.path("toolPrefix").asText(id), contracts,
                    defaultContract, List.copyOf(auth), api));
        }
        root.path("tools").forEach(t -> tools.put(t.path("name").asText(), t));
    }

    /** The manifest embedded in the jar. */
    public static Manifest embedded() {
        try (InputStream in = Manifest.class.getResourceAsStream(CLASSPATH_LOCATION)) {
            if (in == null) {
                throw new ManifestException("No manifest at " + CLASSPATH_LOCATION + " on the classpath; this jar was not built by mcpgp");
            }
            return of(JSON.readTree(in));
        } catch (IOException e) {
            throw new ManifestException("The embedded manifest cannot be read: " + e.getMessage());
        }
    }

    /** A manifest file (development and tests; delivered servers use {@link #embedded()}). */
    public static Manifest file(Path path) {
        try {
            return of(JSON.readTree(Files.readString(path)));
        } catch (IOException e) {
            throw new ManifestException("Cannot read manifest " + path + ": " + e.getMessage());
        }
    }

    public static Manifest of(JsonNode root) {
        String version = root.path("manifestVersion").asText("");
        int major;
        try {
            major = Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            throw new ManifestException("Not an mcpgp manifest (manifestVersion '" + version + "')");
        }
        if (major != SUPPORTED_MAJOR) {
            throw new ManifestException("Manifest version " + version + " is not supported by this runtime (supports "
                    + SUPPORTED_MAJOR + ".x); use a runtime built for it");
        }
        return new Manifest(root);
    }

    public JsonNode root() {
        return root;
    }

    public String serverName() {
        return root.at("/server/name").asText();
    }

    public String serverTitle() {
        return root.at("/server/title").asText(serverName());
    }

    public String serverVersion() {
        return root.at("/server/version").asText();
    }

    public Optional<String> instructions() {
        JsonNode text = root.at("/server/instructions");
        return text.isTextual() ? Optional.of(text.asText()) : Optional.empty();
    }

    public boolean multiInstance() {
        return root.at("/instances/mode").asText("single").equals("multi");
    }

    public List<String> mcpRevisions() {
        List<String> revisions = new ArrayList<>();
        root.at("/runtime/mcpRevisions").forEach(r -> revisions.add(r.asText()));
        return revisions;
    }

    public List<String> allowed(String section) {
        List<String> values = new ArrayList<>();
        root.at("/runtime/" + section + "/allowed").forEach(v -> values.add(v.asText()));
        return values;
    }

    public String defaultOf(String section) {
        JsonNode value = root.at("/runtime/" + section + "/default");
        List<String> allowed = allowed(section);
        return value.isTextual() ? value.asText() : allowed.isEmpty() ? null : allowed.get(0);
    }

    public Map<String, Api> apis() {
        return apis;
    }

    public Map<String, JsonNode> tools() {
        return tools;
    }

    public JsonNode policies() {
        return root.path("policies");
    }

    public JsonNode exposure() {
        return root.path("exposure");
    }
}
