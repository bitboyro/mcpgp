package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Editing a shared naming policy on the platform (spec/platform-api.md §2.3). */
class LibraryEditApiTest extends PlatformTestBase {

    static JsonNode policy() throws Exception {
        return json(send(bearer("GET", "/api/v1/library/naming/acme-standard", null)));
    }

    static HttpResult edit(Map<String, Object> body) throws Exception {
        var response = send(bearer("POST", "/api/v1/library/naming/acme-standard/edits", JSON.writeValueAsBytes(body)));
        return new HttpResult(response.statusCode(), json(response));
    }

    record HttpResult(int status, JsonNode body) {
    }

    @Test
    void aPolicyEditIsPreviewedWithItsUsersAndSavedAsTheNextVersion() throws Exception {
        JsonNode before = policy();
        assertThat(before.at("/policy/vocabulary/add|new").asText()).isEqualTo("create");
        assertThat(before.path("usedBy").findValuesAsText("server")).contains("orders-mcp");
        int base = before.at("/versions").size();
        List<Map<String, Object>> changes = List.of(Map.of("op", "set", "path", "/methodVerbs/PUT", "value", "upsert"),
                Map.of("op", "set", "path", "/vocabulary/obtain", "value", "get"));
        HttpResult preview = edit(Map.of("base", base, "dryRun", true, "changes", changes));
        assertThat(preview.status()).as(preview.body().toString()).isEqualTo(200);
        assertThat(preview.body().path("text").asText()).contains("PUT: upsert").contains("obtain: get").contains("# Shared naming policy");
        assertThat(preview.body().path("problems")).isEmpty();
        assertThat(preview.body().path("usedBy").findValuesAsText("server")).contains("orders-mcp");
        assertThat(policy().at("/versions").size()).isEqualTo(base);

        HttpResult saved = edit(Map.of("base", base, "message", "PUT is an upsert here", "changes", changes));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(201);
        assertThat(saved.body().at("/versions").size()).isEqualTo(base + 1);
        assertThat(saved.body().path("reference").asText()).endsWith("@" + (base + 1));
        assertThat(edit(Map.of("base", base, "changes", changes)).status()).isEqualTo(409);       // not the latest any more
    }

    @Test
    void anInvalidPolicyIsNotSaved() throws Exception {
        int base = policy().at("/versions").size();
        List<Map<String, Object>> changes = List.of(Map.of("op", "set", "path", "/methodVerbs/FETCH", "value", "get"));
        assertThat(edit(Map.of("base", base, "dryRun", true, "changes", changes)).body().path("problems")).isNotEmpty();
        assertThat(edit(Map.of("base", base, "changes", changes)).status()).isEqualTo(422);
        assertThat(json(send(bearer("POST", "/api/v1/library/rules/x/edits", JSON.writeValueAsBytes(Map.of("base", 1, "changes", changes))))).path("detail").asText())
                .contains("only naming policies");
    }
}
