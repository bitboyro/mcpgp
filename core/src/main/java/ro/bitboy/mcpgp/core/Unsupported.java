package ro.bitboy.mcpgp.core;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.diag.Codes;
import ro.bitboy.mcpgp.core.diag.Diagnostics;
import ro.bitboy.mcpgp.core.diag.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings the definition format describes but this version doesn't do yet (definition-format §2,
 * marked "not yet"): an error naming each one, instead of building a server that silently ignores it.
 */
final class Unsupported {

    private Unsupported() {
    }

    static void check(JsonNode document, String file, Diagnostics diagnostics) {
        String mode = document.at("/generation/mode").asText("runtime");
        if (!mode.equals("runtime")) {
            reject(diagnostics, file, "/generation/mode", "generation.mode: " + mode,
                    "generated client code isn't supported yet; use generation.mode: runtime (the default)");
        }
        if (document.at("/schemas/input").asText("full").equals("lean")) {
            reject(diagnostics, file, "/schemas/input", "schemas.input: lean", "lean input schemas aren't supported yet; remove it (full is the default)");
        }
        List<JsonNode> apis = new ArrayList<>();
        if (document.has("api")) {
            apis.add(document.get("api"));
        }
        document.path("apis").forEach(apis::add);
        for (int i = 0; i < apis.size(); i++) {
            JsonNode api = apis.get(i);
            if (api.path("pagination").isObject()) {
                String at = document.has("api") ? "/api/pagination" : "/apis/" + i + "/pagination";
                reject(diagnostics, file, at, "pagination", "pagination isn't supported yet: tools return one page, and agents ask for the next "
                        + "with the API's own parameters; remove it");
            }
        }
        if (document.at("/build/jarSigning").isObject()) {
            reject(diagnostics, file, "/build/jarSigning", "build.jarSigning", "signing the jar isn't supported yet; the package is signed "
                    + "with build.sign (SHA256SUMS.sig), which covers the jar");
        }
        JsonNode image = document.at("/build/image");
        for (String key : List.of("registry", "push", "tar")) {
            JsonNode value = image.path(key);
            if (!value.isMissingNode() && !value.isNull() && !(value.isBoolean() && !value.asBoolean())) {
                reject(diagnostics, file, "/build/image/" + key, "build.image." + key, "building and publishing the image isn't supported yet; "
                        + "the package's docker/ folder has the Dockerfile to build it with docker build");
            }
        }
    }

    private static void reject(Diagnostics diagnostics, String file, String pointer, String setting, String why) {
        diagnostics.error(Codes.NOT_SUPPORTED_YET, setting + " isn't supported yet: " + why, Location.of(file, pointer));
    }
}
