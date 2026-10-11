package ro.bitboy.mcpgp.core.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The text operations of rules: set, prepend, append, replace, template (definition-format §5). */
public final class TextOps {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");

    private TextOps() {
    }

    /** The operation's name ({@code set}, {@code prepend}, …), for provenance. */
    public static String kind(JsonNode op) {
        return op.fieldNames().next();
    }

    public static String apply(JsonNode op, String current, Map<String, String> variables) {
        String value = current == null ? "" : current;
        if (op.has("set")) {
            return render(op.get("set").asText(), variables);
        }
        if (op.has("prepend")) {
            return render(op.get("prepend").asText(), variables) + value;
        }
        if (op.has("append")) {
            return value + render(op.get("append").asText(), variables);
        }
        if (op.has("template")) {
            return render(op.get("template").asText(), variables);
        }
        if (op.has("replace")) {
            JsonNode replace = op.get("replace");
            return Pattern.compile(replace.get("pattern").asText()).matcher(value)
                    .replaceAll(replace.get("with").asText().replace("\\", "\\\\"));
        }
        return value;
    }

    /** Replaces {@code {{name}}} with variables; unknown variables render empty. */
    static String render(String template, Map<String, String> variables) {
        Matcher m = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
