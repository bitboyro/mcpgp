package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The desktop bundle ({@code mcpb} target, packaging.md §7.3): an MCP Bundle ({@code manifest_version}
 * 0.3) that desktop clients install with a click. It holds the server jar and runs it over stdio with
 * the Java on the user's machine (Java 21 or newer). The install dialog asks for the target server and
 * its sign-in when the build has one instance and one API; otherwise for a config file.
 */
public final class Bundle {

    static final String MANIFEST_VERSION = "0.3";
    private static final ObjectMapper JSON = new ObjectMapper();

    private Bundle() {
    }

    /** The bundle's {@code manifest.json}, for a server jar at {@code server/<name>.jar}. */
    public static ObjectNode manifest(JsonNode manifest) {
        String server = manifest.at("/server/name").asText();
        String prefix = server.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_";
        ObjectNode bundle = JSON.createObjectNode();
        bundle.put("manifest_version", MANIFEST_VERSION);
        bundle.put("name", server);
        bundle.put("display_name", manifest.at("/server/title").asText(server));
        bundle.put("version", manifest.at("/server/version").asText().replaceAll("\\+.*$", ""));
        String instructions = manifest.at("/server/instructions").asText("");
        bundle.put("description", firstSentence(instructions.isEmpty() ? "MCP server " + server : instructions));
        bundle.put("long_description", instructions + "\n\nNeeds Java 21 or newer on this computer (`java -version`). The server runs here "
                + "and talks only to the target servers you configure.");
        bundle.putObject("author").put("name", manifest.at("/server/title").asText(server));

        ObjectNode config = JSON.createObjectNode();
        ArrayNode args = JSON.createArrayNode().add("-jar").add("${__dirname}/server/" + server + ".jar");
        ObjectNode env = JSON.createObjectNode();
        // the runtime keeps its audit log and state here, not in the bundle's directory
        env.put(prefix + "STORAGE__ROOT", "${HOME}/." + server);
        List<JsonNode> apis = new ArrayList<>();
        manifest.path("apis").forEach(apis::add);
        boolean simple = apis.size() == 1 && !manifest.at("/instances/mode").asText().equals("multi");
        if (simple) {
            JsonNode api = apis.getFirst();
            config.set("base_url", field("string", "Target server", "The API's address, e.g. https://"
                    + api.path("id").asText() + ".example.com", true, false));
            env.put(prefix + "INSTANCE__BASEURL", "${user_config.base_url}");
            String type = signIn(api);
            if (type != null) {
                env.put(prefix + "INSTANCE__AUTH__TYPE", type);
            }
            switch (type == null ? "" : type) {
                case "basic" -> {
                    config.set("username", field("string", "User name", "For signing in to the API", true, false));
                    config.set("password", field("string", "Password", "For signing in to the API", true, true));
                    env.put(prefix + "INSTANCE__AUTH__USERNAME", "${user_config.username}");
                    env.put(prefix + "INSTANCE__AUTH__PASSWORD", "${user_config.password}");
                }
                case "bearer" -> {
                    config.set("token", field("string", "Token", "The API token", true, true));
                    env.put(prefix + "INSTANCE__AUTH__TOKEN", "${user_config.token}");
                }
                case "apiKey" -> {
                    config.set("api_key", field("string", "API key", "The API key", true, true));
                    env.put(prefix + "INSTANCE__AUTH__VALUE", "${user_config.api_key}");
                }
                case "oauth2ClientCredentials" -> {
                    config.set("token_url", field("string", "Token URL", "The OAuth token endpoint", true, false));
                    config.set("client_id", field("string", "Client ID", "The OAuth client", true, false));
                    config.set("client_secret", field("string", "Client secret", "The OAuth client's secret", true, true));
                    env.put(prefix + "INSTANCE__AUTH__TOKENURL", "${user_config.token_url}");
                    env.put(prefix + "INSTANCE__AUTH__CLIENTID", "${user_config.client_id}");
                    env.put(prefix + "INSTANCE__AUTH__CLIENTSECRET", "${user_config.client_secret}");
                }
                default -> {
                }
            }
        } else {
            config.set("config", field("file", "Configuration file", "Your " + server + ".yaml with the target servers (see "
                    + server + ".example.yaml in the package)", true, false));
            args.add("--config").add("${user_config.config}");
        }
        ObjectNode serverNode = bundle.putObject("server");
        serverNode.put("type", "binary");
        serverNode.put("entry_point", "server/" + server + ".jar");
        ObjectNode mcpConfig = serverNode.putObject("mcp_config");
        mcpConfig.put("command", "java");
        mcpConfig.set("args", args);
        mcpConfig.set("env", env);
        if (!config.isEmpty()) {
            bundle.set("user_config", config);
        }

        ArrayNode tools = bundle.putArray("tools");
        listed(manifest).forEach(t -> tools.addObject().put("name", t.path("name").asText())
                .put("description", firstSentence(t.path("description").asText(t.path("title").asText("")))));
        return bundle;
    }

    /** The sign-in the dialog asks for: the first the API supports (mTLS needs files, so a config file). */
    private static String signIn(JsonNode api) {
        for (JsonNode type : api.at("/downstreamAuth/supported")) {
            if (!type.asText().equals("mtls") && !type.asText().startsWith("custom:")) {
                return type.asText();
            }
        }
        return null;
    }

    /** What tools/list shows in the build's exposure mode (tools-contract §15). */
    private static List<JsonNode> listed(JsonNode manifest) {
        String mode = manifest.at("/exposure/mode").asText("plain");
        List<String> pinned = new ArrayList<>();
        manifest.at("/exposure/pinned").forEach(p -> pinned.add(p.asText()));
        List<JsonNode> tools = new ArrayList<>();
        for (JsonNode tool : manifest.path("tools")) {
            boolean api = tool.path("kind").asText().equals("api");
            if (mode.equals("plain") || !api || mode.equals("hybrid") && pinned.contains(tool.path("name").asText())) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private static ObjectNode field(String type, String title, String description, boolean required, boolean sensitive) {
        ObjectNode field = JSON.createObjectNode().put("type", type).put("title", title).put("description", description).put("required", required);
        if (sensitive) {
            field.put("sensitive", true);
        }
        return field;
    }

    static String firstSentence(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        int end = flat.indexOf(". ");
        String first = end > 0 ? flat.substring(0, end + 1) : flat;
        return first.length() <= 200 ? first : first.substring(0, 199) + "…";
    }
}
