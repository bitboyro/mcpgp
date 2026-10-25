package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code <server>.example.yaml}: a commented starting point for the customer, valid against the
 * build's config schema. Secrets appear only as references; values go to {@code .env}.
 */
public final class ExampleConfig {

    private ExampleConfig() {
    }

    public static String yaml(JsonNode manifest) {
        String server = manifest.at("/server/name").asText();
        boolean multi = manifest.at("/instances/mode").asText().equals("multi");
        List<String> lines = new ArrayList<>();
        lines.add("# " + server + " " + manifest.at("/server/version").asText() + " — example configuration.");
        lines.add("# Copy to " + server + ".yaml (or run `java -jar " + server + ".jar init`), then adjust.");
        lines.add("# Secrets are references (${env:NAME}); put the values in .env next to this file.");
        lines.add("# Every key is described in docs/CONFIGURATION.md; the schema is server.config.schema.json.");
        lines.add("");
        if (contains(manifest.at("/runtime/transports/allowed"), "http")) {
            String transport = manifest.at("/runtime/transports/default").asText("stdio");
            lines.add("server:");
            lines.add("  transport: " + transport + "            # " + String.join(" | ", texts(manifest.at("/runtime/transports/allowed"))));
            lines.add("  # host: 127.0.0.1");
            lines.add("  # port: 8080");
            lines.add("  # auth: { mode: static, keys: [ { name: team-a, value: \"${env:KEY_TEAM_A}\" } ] }");
            lines.add("");
        }
        List<JsonNode> apis = new ArrayList<>();
        manifest.path("apis").forEach(apis::add);
        boolean several = apis.size() > 1;
        if (several) {
            lines.add("apis:");
        }
        for (JsonNode api : apis) {
            String indent = several ? "    " : "";
            if (several) {
                lines.add("  " + api.path("id").asText() + ":");
            }
            lines.add(indent + (multi ? "instances:" : "instance:"));
            String item = indent + (multi ? "  - " : "  ");
            String more = indent + (multi ? "    " : "  ");
            String id = multi ? "prod" : api.path("id").asText();
            if (multi) {
                lines.add(item + "id: prod                 # the value agents pass as `instance`");
                lines.add(more + "baseUrl: https://" + api.path("id").asText() + ".example.com");
            } else {
                lines.add(item + "baseUrl: https://" + api.path("id").asText() + ".example.com");
            }
            List<String> versions = new ArrayList<>();
            api.path("contracts").forEach(c -> versions.add(c.path("version").asText()));
            if (versions.size() > 1) {
                lines.add(more + "contract: auto             # auto | " + String.join(" | ", versions.stream().map(v -> "\"" + v + "\"").toList()));
            }
            List<String> supported = texts(api.at("/upstreamAuth/supported")).stream().filter(t -> !t.equals("mtls")).toList();
            String type = supported.isEmpty() ? "none" : supported.get(0);
            String var = (id + "_").toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            lines.add(more + "auth:" + (supported.size() > 1 ? "                      # type: " + String.join(" | ", supported) : ""));
            lines.add(more + "  type: " + type);
            switch (type) {
                case "basic" -> {
                    lines.add(more + "  username: ${env:" + var + "USERNAME}");
                    lines.add(more + "  password: ${env:" + var + "PASSWORD}");
                }
                case "bearer" -> lines.add(more + "  token: ${env:" + var + "TOKEN}");
                case "apiKey" -> lines.add(more + "  value: ${env:" + var + "API_KEY}");
                case "oauth2ClientCredentials" -> {
                    lines.add(more + "  tokenUrl: https://login.example.com/oauth2/token");
                    lines.add(more + "  clientId: ${env:" + var + "CLIENT_ID}");
                    lines.add(more + "  clientSecret: ${env:" + var + "CLIENT_SECRET}");
                }
                default -> {
                }
            }
            if (api.at("/upstreamAuth/fromRequest").isObject()) {
                lines.add(more + "# or the client's own credential, from a request header (HTTP only), then the one above when it's missing:");
                lines.add(more + "# auth: [ { type: fromRequest }, { type: " + type + ", … } ]");
            }
            if (api.at("/upstreamAuth/tokenExchange").isObject()) {
                lines.add(more + "# or as the signed-in user (server.auth.mode: oauth): their sign-in exchanged for a token for the API:");
                lines.add(more + "# auth: { type: tokenExchange, tokenUrl: https://login.example.com/oauth2/token, clientId: …, clientSecret: …, audience: … }");
            }
            lines.add(more + "# tls: { ca: { path: ./certs/ca.pem } }   # for internal CAs; or `instance trust`");
        }
        lines.add("");
        lines.add("# audit: { file: { path: ./audit/audit.jsonl, retainDays: 30 }, arguments: redacted }");
        return String.join("\n", lines) + "\n";
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static boolean contains(JsonNode array, String value) {
        return texts(array).contains(value);
    }
}
