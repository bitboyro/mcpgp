package ro.bitboy.mcpgp.platform;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Big answers can be read in parts: the manifest's fields, the validation's level and limit. */
class TrimmedAnswersApiTest extends PlatformTestBase {

    static final String VERSION = "/api/v1/servers/orders-mcp/versions/latest";

    @Test
    void theManifestIsReadOnePartAtATime() throws Exception {
        JsonNode whole = json(send(bearer("GET", VERSION + "/manifest", null)));
        JsonNode part = json(send(bearer("GET", VERSION + "/manifest?fields=tools", null)));
        assertThat(part.fieldNames()).toIterable().containsExactly("tools");
        assertThat(part.path("tools")).isEqualTo(whole.path("tools"));

        // in the order asked for
        JsonNode two = json(send(bearer("GET", VERSION + "/manifest?fields=tools,%20apis", null)));
        List<String> names = new ArrayList<>();
        two.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("tools", "apis");

        var unknown = send(bearer("GET", VERSION + "/manifest?fields=nope", null));
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(new String(unknown.body())).contains("no 'nope'").contains("tools");
    }

    @Test
    void validationCountsEveryLevelAndReturnsTheOnesAskedFor() throws Exception {
        JsonNode all = json(send(bearer("POST", VERSION + "/validate", null)));
        int total = all.path("diagnostics").size();
        assertThat(all.at("/counts/error").asInt() + all.at("/counts/warning").asInt() + all.at("/counts/info").asInt()).isEqualTo(total);
        assertThat(all.has("omitted")).isFalse();

        JsonNode important = json(send(bearer("POST", VERSION + "/validate?level=warning", null)));
        assertThat(important.path("counts")).isEqualTo(all.path("counts"));
        assertThat(important.path("diagnostics").findValuesAsText("level")).doesNotContain("info");
        assertThat(important.path("diagnostics").size()).isEqualTo(all.at("/counts/error").asInt() + all.at("/counts/warning").asInt());

        JsonNode capped = json(send(bearer("POST", VERSION + "/validate?limit=1", null)));
        if (total > 1) {
            assertThat(capped.path("diagnostics")).hasSize(1);
            assertThat(capped.path("omitted").asInt()).isEqualTo(total - 1);
        }

        assertThat(send(bearer("POST", VERSION + "/validate?level=loud", null)).statusCode()).isEqualTo(422);
    }
}
