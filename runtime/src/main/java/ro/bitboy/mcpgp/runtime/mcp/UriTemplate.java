package ro.bitboy.mcpgp.runtime.mcp;

import io.modelcontextprotocol.util.McpUriTemplateManager;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The resource URI templates the compiler writes (tools-contract §14.1): path variables
 * ({@code {id}}, one segment each) and one query expression ({@code {?a,b}}, RFC 6570 form-style).
 * A URI matches when its path matches; query parameters that aren't declared are dropped.
 */
final class UriTemplate implements McpUriTemplateManager {

    private static final Pattern VARIABLE = Pattern.compile("\\{([^}?]+)}");
    private final String template;
    private final Pattern path;
    private final List<String> pathNames = new ArrayList<>();
    private final List<String> queryNames = new ArrayList<>();

    UriTemplate(String template) {
        this.template = template;
        String pathPart = template;
        int query = template.indexOf("{?");
        if (query >= 0) {
            for (String name : template.substring(query + 2, template.indexOf('}', query)).split(",")) {
                queryNames.add(name.strip());
            }
            pathPart = template.substring(0, query);
        }
        StringBuilder regex = new StringBuilder("^");
        Matcher m = VARIABLE.matcher(pathPart);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(pathPart.substring(last, m.start()))).append("([^/?#]+)");
            pathNames.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(pathPart.substring(last))).append("(?:\\?.*)?$");
        this.path = Pattern.compile(regex.toString());
    }

    @Override
    public List<String> getVariableNames() {
        List<String> names = new ArrayList<>(pathNames);
        names.addAll(queryNames);
        return names;
    }

    List<String> pathNames() {
        return pathNames;
    }

    List<String> queryNames() {
        return queryNames;
    }

    @Override
    public boolean matches(String uri) {
        return path.matcher(uri).matches();
    }

    @Override
    public boolean isUriTemplate(String uri) {
        return uri.contains("{");
    }

    /** Path and declared query variables, decoded; undeclared query parameters are left out. */
    @Override
    public Map<String, String> extractVariableValues(String uri) {
        Map<String, String> values = new LinkedHashMap<>();
        Matcher m = path.matcher(uri);
        if (!m.matches()) {
            return values;
        }
        for (int i = 0; i < pathNames.size(); i++) {
            values.put(pathNames.get(i), URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
        }
        int q = uri.indexOf('?');
        if (q >= 0) {
            for (String pair : uri.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                if (queryNames.contains(name)) {
                    values.put(name, eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    /** Query parameter names in the URI that the template doesn't declare (dropped, and logged by the caller). */
    List<String> undeclared(String uri) {
        List<String> names = new ArrayList<>();
        int q = uri.indexOf('?');
        if (q >= 0) {
            for (String pair : uri.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                if (!name.isEmpty() && !queryNames.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    @Override
    public String toString() {
        return template;
    }
}
