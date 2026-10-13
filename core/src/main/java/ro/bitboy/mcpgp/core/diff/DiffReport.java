package ro.bitboy.mcpgp.core.diff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link ManifestDiff.Result} for people: plain text for terminals, Markdown for pull-request comments. */
public final class DiffReport {

    private DiffReport() {
    }

    private static final Map<ManifestDiff.Impact, String> HEADINGS = Map.of(
            ManifestDiff.Impact.BREAKING, "Breaking: agents or clients of the earlier build need updating",
            ManifestDiff.Impact.NOTABLE, "Worth a look",
            ManifestDiff.Impact.INFO, "Other changes");

    public static String text(String server, ManifestDiff.Result result) {
        StringBuilder out = new StringBuilder(server).append(": ").append(summary(result)).append('\n');
        for (Map.Entry<ManifestDiff.Impact, List<ManifestDiff.Change>> group : grouped(result).entrySet()) {
            out.append('\n').append(HEADINGS.get(group.getKey())).append('\n');
            for (ManifestDiff.Change c : group.getValue()) {
                out.append("  ").append(c.tool() == null ? "server" : c.tool()).append(": ").append(c.text()).append('\n');
            }
        }
        return out.toString();
    }

    public static String markdown(String server, ManifestDiff.Result result) {
        StringBuilder md = new StringBuilder("### ").append(server).append(": ").append(summary(result)).append("\n");
        for (Map.Entry<ManifestDiff.Impact, List<ManifestDiff.Change>> group : grouped(result).entrySet()) {
            md.append("\n**").append(HEADINGS.get(group.getKey())).append("**\n\n");
            for (ManifestDiff.Change c : group.getValue()) {
                md.append("- ").append(c.tool() == null ? "" : "`" + c.tool() + "`: ").append(escape(c.text())).append('\n');
            }
        }
        return md.toString();
    }

    /** "3 changes since 1.4.0, 1 breaking", or "no changes since 1.4.0". */
    public static String summary(ManifestDiff.Result result) {
        String since = result.from().isEmpty() ? "" : " since " + result.from();
        if (result.empty()) {
            return "no changes" + since;
        }
        int n = result.changes().size();
        long breaking = result.breaking();
        return n + (n == 1 ? " change" : " changes") + since + (breaking > 0 ? ", " + breaking + " breaking" : ", none breaking");
    }

    private static Map<ManifestDiff.Impact, List<ManifestDiff.Change>> grouped(ManifestDiff.Result result) {
        Map<ManifestDiff.Impact, List<ManifestDiff.Change>> groups = new LinkedHashMap<>();
        for (ManifestDiff.Impact impact : ManifestDiff.Impact.values()) {
            List<ManifestDiff.Change> list = result.changes().stream().filter(c -> c.impact() == impact).toList();
            if (!list.isEmpty()) {
                groups.put(impact, list);
            }
        }
        return groups;
    }

    private static String escape(String text) {
        return text.replace("|", "\\|").replace("<", "&lt;");
    }
}
