package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether an instance's credential can call an operation (auth.md §2.2): the operation's security
 * requirements are alternatives (OR) of sets (AND) of schemes; a credential satisfies a set when it
 * covers every scheme in it, a client certificate covering {@code mtls} next to any credential. An
 * instance without credentials ({@code none}) isn't judged: it sends nothing and the API decides.
 */
public final class Satisfaction {

    private Satisfaction() {
    }

    /**
     * Null when {@code entry} satisfies one alternative of {@code security}, else what the closest
     * alternative needs, in words ("oauth with the scopes orders.write").
     *
     * @param security the variant's {@code security}; missing means nothing is known, so anything goes
     * @param kinds    the API's {@code downstreamAuth.schemes}: scheme name → kind
     */
    public static String unmet(JsonNode entry, RuntimeConfig.Instance instance, JsonNode security, JsonNode kinds) {
        if (security == null || !security.isArray() || security.isEmpty()) {
            return null;
        }
        List<String> needs = new ArrayList<>();
        for (JsonNode alternative : security) {
            List<String> missing = new ArrayList<>();
            for (JsonNode requirement : alternative) {
                String scheme = requirement.path("scheme").asText();
                List<String> scopes = new ArrayList<>();
                requirement.path("scopes").forEach(s -> scopes.add(s.asText()));
                String kind = kinds.path(scheme).asText(null);
                if (!covers(entry, instance, kind, scopes)) {
                    missing.add(scheme + (scopes.isEmpty() ? "" : " with the scope" + (scopes.size() == 1 ? " " : "s ") + String.join(", ", scopes)));
                }
            }
            if (missing.isEmpty()) {
                return null;
            }
            needs.add(String.join(" and ", missing));
        }
        return String.join(", or ", needs);
    }

    /** Null when one of the instance's credentials can call the operation; else what it needs. */
    public static String unmet(RuntimeConfig.Instance instance, JsonNode security, JsonNode kinds) {
        String first = null;
        for (JsonNode entry : instance.authEntries()) {
            String unmet = unmet(entry, instance, security, kinds);
            if (unmet == null) {
                return null;
            }
            first = first == null ? unmet : first;
        }
        return first;
    }

    private static boolean covers(JsonNode entry, RuntimeConfig.Instance instance, String kind, List<String> scopes) {
        String type = entry.path("type").asText("none");
        if (kind == null) {
            return true; // a scheme the build didn't describe: don't guess against it
        }
        if (kind.equals("mtls")) {
            return instance.tls().has("clientCert");
        }
        if (type.equals("fromRequest") || type.equals("none")) {
            // what the client sends is only known per call, and an instance without credentials (a mock, a
            // gateway that adds them) sends none: the API decides
            return true;
        }
        return switch (kind) {
            case "basic" -> type.equals("basic");
            case "apiKey" -> type.equals("apiKey");
            // a static bearer token may be an OAuth access token: its scopes aren't known here
            case "bearer" -> type.equals("bearer") || type.equals("oauth2ClientCredentials");
            // scopes are checked when the config names them; without, the identity provider grants its defaults
            case "oauth2ClientCredentials", "oauth2" -> type.equals("bearer")
                    || type.equals("oauth2ClientCredentials") && (!entry.has("scopes") || scopesOf(entry).containsAll(scopes));
            default -> false;
        };
    }

    private static List<String> scopesOf(JsonNode entry) {
        List<String> scopes = new ArrayList<>();
        entry.path("scopes").forEach(s -> scopes.add(s.asText()));
        return scopes;
    }
}
