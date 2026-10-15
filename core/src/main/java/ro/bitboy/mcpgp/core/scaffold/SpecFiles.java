package ro.bitboy.mcpgp.core.scaffold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * An API's spec as uploaded: one file, or several that reference each other with {@code $ref}
 * (a folder or a zip of it). Finds the main file (the one with {@code swagger:} or {@code openapi:}
 * that no other file references), follows every {@code $ref} from it, and says what is missing
 * before anything is created: files that aren't in the upload, references to URLs, versions the
 * compiler doesn't read.
 */
public final class SpecFiles {

    private static final YAMLMapper YAML = new YAMLMapper();

    /** A {@code $ref} that can't be followed: where it points, the file it is in, and where in that file. */
    public record Ref(String target, String from, String pointer) {
    }

    /**
     * @param main       the main file, or null when it isn't clear (see {@code candidates}) or there is none
     * @param candidates files that could be the main one, when there are several
     * @param used       the files the main one needs (itself included), in the order they were found
     * @param missing    references to files that aren't in the upload
     * @param broken     references to a part that isn't there ({@code #/components/schemas/Nope}), in the
     *                   spec itself or in a referenced file; {@code target} is {@code file#/pointer}
     * @param remote     references to URLs (the platform and builds never fetch them)
     * @param problems   everything else, in plain words
     */
    public record Analysis(String main, List<String> candidates, String format, String title, String version, Set<String> used,
                           List<Ref> missing, List<Ref> broken, List<Ref> remote, List<String> problems) {
        public boolean ok() {
            return main != null && missing.isEmpty() && broken.isEmpty() && remote.isEmpty() && problems.isEmpty();
        }
    }

    private SpecFiles() {
    }

    /** The files that can be part of a spec (YAML and JSON), without folders systems add to zips. */
    public static Map<String, byte[]> specFiles(Map<String, byte[]> files) {
        Map<String, byte[]> kept = new TreeMap<>();
        files.forEach((path, content) -> {
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (path.startsWith("__MACOSX/") || name.startsWith(".") || !name.matches("(?i).+\\.(ya?ml|json)")) {
                return;
            }
            kept.put(path, content);
        });
        return kept;
    }

    /** @param chosen the main file the user picked, or null to find it */
    public static Analysis analyze(Map<String, byte[]> files, String chosen) {
        List<String> problems = new ArrayList<>();
        Map<String, JsonNode> docs = new TreeMap<>();
        files.forEach((path, content) -> {
            try {
                JsonNode doc = YAML.readTree(content);
                if (doc != null) {
                    docs.put(path, doc);
                }
            } catch (IOException e) {
                problems.add(path + " isn't valid YAML or JSON: " + firstLine(e.getMessage()));
            }
        });
        if (docs.isEmpty()) {
            problems.add(files.isEmpty() ? "no files" : "no YAML or JSON file");
            return new Analysis(null, List.of(), null, null, null, Set.of(), List.of(), List.of(), List.of(), problems);
        }

        List<String> specs = docs.entrySet().stream().filter(e -> isSpec(e.getValue())).map(Map.Entry::getKey).toList();
        Set<String> referenced = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> e : docs.entrySet()) {
            for (Ref ref : refs(e.getKey(), e.getValue())) {
                if (!isRemote(ref.target())) {
                    referenced.add(ref.target());
                }
            }
        }
        String main;
        List<String> candidates = List.of();
        if (chosen != null) {
            if (!specs.contains(chosen)) {
                problems.add(chosen + " is not an OpenAPI or Swagger document in the upload");
                return new Analysis(null, specs, null, null, null, Set.of(), List.of(), List.of(), List.of(), problems);
            }
            main = chosen;
        } else if (specs.isEmpty()) {
            problems.add("none of the files is an OpenAPI or Swagger document (no top-level openapi: or swagger: field)");
            return new Analysis(null, List.of(), null, null, null, Set.of(), List.of(), List.of(), List.of(), problems);
        } else {
            List<String> top = specs.stream().filter(s -> !referenced.contains(s)).toList();
            List<String> options = top.isEmpty() ? specs : top;
            if (options.size() == 1) {
                main = options.get(0);
            } else {
                // several specs: the user says which one is the API's
                return new Analysis(null, options, null, null, null, Set.of(), List.of(), List.of(), List.of(), problems);
            }
        }

        JsonNode doc = docs.get(main);
        String format = format(doc, problems);
        String title = doc.at("/info/title").asText("").strip();
        String version = doc.at("/info/version").asText("").strip();

        // follow the references from the main file
        Set<String> used = new LinkedHashSet<>();
        List<Ref> missing = new ArrayList<>();
        List<Ref> remote = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>(List.of(main));
        while (!queue.isEmpty()) {
            String file = queue.poll();
            if (!used.add(file)) {
                continue;
            }
            for (Ref ref : refs(file, docs.get(file))) {
                if (isRemote(ref.target())) {
                    remote.add(ref);
                } else if (ref.target().startsWith("../")) {
                    problems.add(ref.from() + " refers to " + ref.target() + ", outside the uploaded files (at " + ref.pointer() + ")");
                } else if (!docs.containsKey(ref.target())) {
                    if (missing.stream().noneMatch(m -> m.target().equals(ref.target()))) {
                        missing.add(ref);
                    }
                } else {
                    queue.add(ref.target());
                }
            }
        }
        // every reference of the files in use points to something: a part of the same file, or of another one
        List<Ref> broken = new ArrayList<>();
        for (String file : used) {
            collectAll(file, docs.get(file), "", (value, pointer) -> {
                int hash = value.indexOf('#');
                String filePart = hash < 0 ? value : value.substring(0, hash);
                String fragment = hash < 0 ? "" : value.substring(hash + 1);
                if (isRemote(filePart)) {
                    return;
                }
                String target = filePart.isEmpty() ? file : resolve(file, filePart);
                JsonNode targetDoc = docs.get(target);
                if (targetDoc == null || fragment.isEmpty()) {
                    return;         // a missing file is reported above
                }
                if (targetDoc.at(decode(fragment)).isMissingNode()) {
                    broken.add(new Ref(target + "#" + fragment, file, pointer));
                }
            });
        }
        return new Analysis(main, List.of(), format, title, version, used, missing, broken, remote, problems);
    }

    /** Every {@code $ref} of a document, with where it is. */
    private static void collectAll(String file, JsonNode node, String pointer, java.util.function.BiConsumer<String, String> found) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                found.accept(ref.asText(), pointer + "/$ref");
            }
            node.properties().forEach(e -> {
                if (!e.getKey().equals("$ref")) {
                    collectAll(file, e.getValue(), pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"), found);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectAll(file, node.get(i), pointer + "/" + i, found);
            }
        }
    }

    /** A URI fragment as a JSON pointer: percent-decoded ({@code %7Bid%7D} → {@code {id}}). */
    private static String decode(String fragment) {
        try {
            return java.net.URLDecoder.decode(fragment.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return fragment;
        }
    }

    private static boolean isSpec(JsonNode doc) {
        return doc.isObject() && (doc.has("openapi") || doc.has("swagger"));
    }

    private static String format(JsonNode doc, List<String> problems) {
        String swagger = doc.path("swagger").asText("");
        String openapi = doc.path("openapi").asText("");
        if (swagger.equals("2.0")) {
            return "swagger-2.0";
        }
        if (openapi.startsWith("3.0")) {
            return "openapi-3.0";
        }
        if (openapi.startsWith("3.1")) {
            return "openapi-3.1";
        }
        if (openapi.startsWith("3.2")) {
            problems.add("OpenAPI " + openapi + " isn't supported yet (Swagger 2.0, OpenAPI 3.0 and 3.1 are)");
        } else {
            problems.add("unknown version " + (openapi.isEmpty() ? "swagger " + swagger : "openapi " + openapi)
                    + " (Swagger 2.0, OpenAPI 3.0 and 3.1 are read)");
        }
        return null;
    }

    /** The {@code $ref}s of a file that point to another file or a URL, resolved against the file's folder. */
    static List<Ref> refs(String file, JsonNode doc) {
        List<Ref> refs = new ArrayList<>();
        collect(file, doc, "", refs);
        return refs;
    }

    private static void collect(String file, JsonNode node, String pointer, List<Ref> refs) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) {
                String value = ref.asText();
                String target = value.contains("#") ? value.substring(0, value.indexOf('#')) : value;
                refs.add(new Ref(isRemote(target) ? target : resolve(file, target), file, pointer + "/$ref"));
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getKey().equals("$ref")) {
                    collect(file, e.getValue(), pointer + "/" + e.getKey().replace("~", "~0").replace("/", "~1"), refs);
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collect(file, node.get(i), pointer + "/" + i, refs);
            }
        }
    }

    static boolean isRemote(String target) {
        return target.startsWith("http://") || target.startsWith("https://");
    }

    /** {@code target} relative to the folder of {@code from}; leading "../" stays when it leaves the upload. */
    static String resolve(String from, String target) {
        Deque<String> parts = new ArrayDeque<>();
        int slash = from.lastIndexOf('/');
        if (slash > 0) {
            for (String p : from.substring(0, slash).split("/")) {
                parts.addLast(p);
            }
        }
        int outside = 0;
        for (String p : target.replace('\\', '/').split("/")) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (parts.isEmpty()) {
                    outside++;
                } else {
                    parts.removeLast();
                }
            } else {
                parts.addLast(p);
            }
        }
        return "../".repeat(outside) + String.join("/", parts);
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("");
    }

    /** Files moved to where the spec refers to them: {@code from} as uploaded, {@code to} as the spec expects. */
    public record Move(String from, String to) {
    }

    /** An upload with its files where the spec expects them, and what was moved. */
    public record Placed(Map<String, byte[]> files, List<Move> moves) {
    }

    /**
     * Files picked one by one (or dropped) arrive without their folders: {@code entries.json} instead of
     * the {@code paths/entries.json} the spec refers to. A missing file that has exactly one uploaded file
     * of the same name, not used where it is, is moved there. Repeats until nothing more moves.
     */
    public static Placed place(Map<String, byte[]> files, String chosen) {
        Map<String, byte[]> current = new TreeMap<>(files);
        List<Move> moves = new ArrayList<>();
        for (int round = 0; round < 50; round++) {
            Analysis a = analyze(current, chosen);
            Move move = null;
            for (Ref m : a.missing()) {
                String name = m.target().substring(m.target().lastIndexOf('/') + 1);
                List<String> same = current.keySet().stream()
                        .filter(p -> p.substring(p.lastIndexOf('/') + 1).equals(name) && !a.used().contains(p)).toList();
                if (same.size() == 1) {
                    move = new Move(same.get(0), m.target());
                    break;
                }
            }
            if (move == null) {
                break;
            }
            current.put(move.to(), current.remove(move.from()));
            moves.add(move);
        }
        return new Placed(current, moves);
    }

    /** The upload's files under {@code prefix} (e.g. {@code contracts/}), the ones the main file uses first. */
    public static Map<String, byte[]> under(String prefix, Map<String, byte[]> files, Analysis analysis) {
        Map<String, byte[]> placed = new LinkedHashMap<>();
        for (String path : analysis.used()) {
            placed.put(prefix + path, files.get(path));
        }
        return placed;
    }
}
