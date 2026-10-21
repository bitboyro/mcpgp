package ro.bitboy.mcpgp.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The pager on the four list shapes, without a server. */
class ListPagerTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode result(String field, String key, int count) {
        ObjectNode result = JSON.createObjectNode();
        for (int i = 0; i < count; i++) {
            result.withArray(field).addObject().put(key, key + "-" + i);
        }
        return result;
    }

    @Test
    void everyListKindIsCutIntoPagesThatAddUp() {
        String[][] kinds = {{"tools/list", "tools", "name"}, {"prompts/list", "prompts", "name"},
                {"resources/list", "resources", "uri"}, {"resources/templates/list", "resourceTemplates", "uriTemplate"}};
        for (String[] kind : kinds) {
            List<String> seen = new ArrayList<>();
            String cursor = "";
            int pages = 0;
            do {
                ObjectNode page = result(kind[1], kind[2], 7);
                ListPager.page(kind[0], page, cursor, 3);
                page.get(kind[1]).forEach(i -> seen.add(i.path(kind[2]).asText()));
                cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
                pages++;
            } while (cursor != null);
            assertThat(pages).as(kind[0]).isEqualTo(3);
            assertThat(seen).as(kind[0]).hasSize(7).doesNotHaveDuplicates();
        }
    }

    @Test
    void aListThatFitsHasNoNextCursor() {
        ObjectNode page = result("tools", "name", 3);
        ListPager.page("tools/list", page, "", 3);
        assertThat(page.has("nextCursor")).isFalse();
        assertThat(page.get("tools")).hasSize(3);
    }

    @Test
    void aCursorFromAnotherListIsRefused() {
        ObjectNode first = result("tools", "name", 7);
        ListPager.page("tools/list", first, "", 3);
        String cursor = first.get("nextCursor").asText();

        ObjectNode changed = result("tools", "name", 7);
        ((ObjectNode) changed.get("tools").get(5)).put("name", "renamed");      // the list changed meanwhile
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ListPager.page("tools/list", changed, cursor, 3)))
                .isInstanceOf(ListPager.InvalidCursor.class);

        JsonNode answer = ListPager.answer(new ListPager.Pending("tools/list", cursor),
                JSON.createObjectNode().put("jsonrpc", "2.0").put("id", 1).set("result", changed), 3);
        assertThat(answer.at("/error/code").asInt()).isEqualTo(-32602);
    }
}
