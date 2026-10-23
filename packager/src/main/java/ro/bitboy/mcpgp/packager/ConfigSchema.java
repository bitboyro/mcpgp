package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ro.bitboy.mcpgp.schemas.SchemaKind;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code server.config.schema.json} (runtime-config intro): the generic runtime-config schema narrowed
 * to one build, so editors and {@code doctor} only offer what this server supports: its transports and
 * inbound auth modes, {@code instance} or {@code instances} (per API in multi-API servers), its
 * contract versions, its sign-in types, client certificates only with {@code mtls}, and the file
 * input forms it supports.
 */
final class ConfigSchema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigSchema() {
    }

    static ObjectNode generate(JsonNode manifest) throws IOException {
        ObjectNode schema;
        try (InputStream in = Schemas.class.getResourceAsStream("/schemas/" + SchemaKind.RUNTIME_CONFIG.fileName())) {
            schema = (ObjectNode) JSON.readTree(in);
        }
        String server = manifest.at("/server/name").asText();
        // identifies the schema of this build; nothing is hosted under it
        schema.put("$id", "urn:mcpgp:config:" + server + ":" + manifest.at("/server/version").asText());
        schema.put("title", server + " configuration");
        schema.put("description", "Runtime configuration of " + manifest.at("/server/title").asText(server) + " "
                + manifest.at("/server/version").asText() + " (generated for this build; see docs/CONFIGURATION.md).");
        ObjectNode defs = (ObjectNode) schema.get("$defs");
        ObjectNode properties = (ObjectNode) schema.get("properties");

        // server: transports and inbound auth modes of this build
        ObjectNode serverDef = (ObjectNode) defs.get("server");
        enumOf((ObjectNode) serverDef.at("/properties/transport"), manifest.at("/runtime/transports/allowed"));
        enumOf((ObjectNode) serverDef.at("/properties/auth/properties/mode"), manifest.at("/runtime/inboundAuth/allowed"));
        if (!contains(manifest.at("/runtime/transports/allowed"), "http")) {
            properties.remove("server");
        }

        // instances, per API
        boolean multi = manifest.at("/instances/mode").asText().equals("multi");
        List<JsonNode> apis = new ArrayList<>();
        manifest.path("apis").forEach(apis::add);
        ObjectNode instanceTemplate = (ObjectNode) defs.remove("instance");
        ObjectNode authTemplate = (ObjectNode) defs.remove("downstreamAuth");
        for (JsonNode api : apis) {
            String id = api.path("id").asText();
            ObjectNode instance = instanceTemplate.deepCopy();
            ArrayNode versions = JSON.createArrayNode().add("auto");
            api.path("contracts").forEach(c -> versions.add(c.path("version").asText()));
            ((ObjectNode) instance.at("/properties/contract")).set("enum", versions);
            List<String> supported = new ArrayList<>();
            api.at("/downstreamAuth/supported").forEach(s -> supported.add(s.asText()));
            ObjectNode auth = authTemplate.deepCopy();
            ArrayNode branches = JSON.createArrayNode();
            for (JsonNode branch : auth.path("oneOf")) {
                String type = branch.at("/properties/type/const").asText();
                boolean fromRequest = type.equals("fromRequest") && api.at("/downstreamAuth/fromRequest").isObject();
                boolean exchange = type.equals("tokenExchange") && api.at("/downstreamAuth/tokenExchange").isObject();
                if (type.equals("none") || supported.contains(type) || fromRequest || exchange) {
                    branches.add(branch);
                }
            }
            auth.set("oneOf", branches);
            defs.set("auth_" + id, auth);
            // one credential, or a list tried in order (auth.md §2.4)
            ObjectNode one = JSON.createObjectNode().put("$ref", "#/$defs/auth_" + id);
            ObjectNode authProperty = JSON.createObjectNode();
            authProperty.putArray("oneOf").add(one).add(JSON.createObjectNode().put("type", "array").put("minItems", 1).set("items", one.deepCopy()));
            ((ObjectNode) instance.get("properties")).set("auth", authProperty);
            if (!supported.contains("mtls")) {
                ObjectNode tls = (ObjectNode) instance.at("/properties/tls/properties");
                tls.remove(List.of("clientCert", "clientKey", "password"));
            }
            if (multi) {
                ((ArrayNode) instance.withArray("required")).add("id");
            } else {
                ((ObjectNode) instance.get("properties")).remove("id");
            }
            defs.set("instance_" + id, instance);
        }
        String key = multi ? "instances" : "instance";
        properties.remove(multi ? "instance" : "instances");
        if (apis.size() > 1) {
            properties.remove("instance");
            properties.remove("instances");
            ObjectNode apisNode = JSON.createObjectNode().put("type", "object");
            ObjectNode apiProperties = apisNode.putObject("properties");
            for (JsonNode api : apis) {
                String id = api.path("id").asText();
                ObjectNode entry = apiProperties.putObject(id).put("type", "object");
                ObjectNode entryProperties = entry.putObject("properties");
                entryProperties.putObject("tools").put("$ref", "#/$defs/toolPolicy");
                entryProperties.putObject("settings").put("$ref", "#/$defs/inheritedSettings");
                entryProperties.set(key, reference(id, multi));
                entry.putArray("required").add(key);
                entry.put("additionalProperties", false);
            }
            apisNode.put("additionalProperties", false);
            properties.set("apis", apisNode);
        } else {
            properties.remove("apis");
            properties.set(key, reference(apis.get(0).path("id").asText(), multi));
        }

        // file input forms
        List<String> forms = new ArrayList<>();
        manifest.at("/policies/files/forms").forEach(f -> forms.add(f.asText()));
        ObjectNode files = (ObjectNode) properties.at("/files/properties");
        for (String form : List.of("base64", "path")) {
            if (!forms.contains(form)) {
                files.remove(form);
            }
        }
        // only the packs this build has
        ObjectNode packs = JSON.createObjectNode().put("type", "object");
        ObjectNode packProperties = packs.putObject("properties");
        manifest.path("packs").forEach(p -> packProperties.putObject(p.path("id").asText()).put("type", "object"));
        packs.put("additionalProperties", false);
        properties.set("packs", packs);
        return schema;
    }

    private static ObjectNode reference(String api, boolean multi) {
        ObjectNode ref = JSON.createObjectNode().put("$ref", "#/$defs/instance_" + api);
        if (!multi) {
            return ref;
        }
        ObjectNode list = JSON.createObjectNode().put("type", "array").put("minItems", 1);
        list.set("items", ref);
        return list;
    }

    private static void enumOf(ObjectNode node, JsonNode allowed) {
        if (allowed.isArray() && !allowed.isEmpty()) {
            node.set("enum", allowed.deepCopy());
        }
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) {
            if (item.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
