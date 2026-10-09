package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** Names of built-in and pack tools, through the same namespace, casing and limit steps as API tools. */
public final class BuiltinNames {

    private final Naming naming;

    public BuiltinNames(JsonNode namingPolicy) {
        this.naming = new Naming(namingPolicy);
    }

    /**
     * @param base      the tool's base name, e.g. {@code instances_list}
     * @param apiPrefix the API prefix to add, or null for server-wide tools
     */
    public String name(String base, String apiPrefix) {
        String name = naming.cased(naming.namespace(base, apiPrefix, apiPrefix != null));
        String limited = naming.limit(name);
        return limited == null ? name : limited;
    }
}
