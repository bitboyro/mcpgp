package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.core.openapi.ApiModel.Operation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches selectors (definition-format §5) against operations. All given fields must match (AND);
 * list values match if any element matches (OR).
 */
final class Selectors {

    private Selectors() {
    }

    /**
     * @param contracts the contract versions in which this operation exists (and is still selected)
     */
    static boolean matches(JsonNode selector, String api, Operation op, Collection<String> contracts) {
        if (selector.has("api") && !selector.get("api").asText().equals(api)) {
            return false;
        }
        if (selector.has("tag") && values(selector.get("tag")).stream().noneMatch(op.tags()::contains)) {
            return false;
        }
        if (selector.has("method") && values(selector.get("method")).stream().noneMatch(m -> m.equalsIgnoreCase(op.method()))) {
            return false;
        }
        if (selector.has("path") && values(selector.get("path")).stream().noneMatch(p -> glob(p).matcher(op.path()).matches())) {
            return false;
        }
        if (selector.has("operationId") && !text(selector.get("operationId").asText(), op.operationId())) {
            return false;
        }
        if (selector.has("contract") && !contracts.contains(selector.get("contract").asText())) {
            return false;
        }
        if (selector.has("extension")) {
            var fields = selector.get("extension").fields();
            while (fields.hasNext()) {
                var f = fields.next();
                JsonNode actual = op.extensions().get(f.getKey());
                if (actual == null || !actual.equals(f.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Exact match, or a regular expression when the value starts with {@code ~}. */
    static boolean text(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        if (expected.startsWith("~")) {
            return Pattern.compile(expected.substring(1)).matcher(actual).find();
        }
        return expected.equals(actual);
    }

    /** Glob over URL paths: {@code **} crosses segments, {@code *} and {@code ?} don't. */
    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        } else {
            values.add(node.asText());
        }
        return values;
    }
}
