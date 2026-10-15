package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Uploaded projects (spec/platform-api.md §2.1, §8): a zip of the project, or JSON
 * {@code {files: {path: content}}}. Paths must stay inside the project, sizes are capped, and there is
 * exactly one root definition ({@code *.mcpgp.yaml} at the top). The result is an immutable,
 * content-addressed version.
 */
public final class Projects {

    static final int MAX_FILE = 10 * 1024 * 1024;
    static final int MAX_PROJECT = 50 * 1024 * 1024;
    static final int MAX_FILES = 2000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Store store;

    public Projects(Store store) {
        this.store = store;
    }

    /** Reads an upload of a server project: {@code application/zip} or {@code application/json}. */
    public Model.Version store(byte[] body, String contentType) {
        return store(read(body, contentType), null, true);
    }

    /** Reads an upload of a library item: its root is {@code root}, or its only file. */
    public Model.Version storeItem(byte[] body, String contentType, String root) {
        return store(read(body, contentType), root, false);
    }

    private static Map<String, byte[]> read(byte[] body, String contentType) {
        return contentType != null && contentType.startsWith("application/zip") ? unzip(body) : fromJson(body);
    }

    /** The files of an upload (zip or JSON), with the same limits as a project, and without a folder they all share. */
    public static Map<String, byte[]> files(byte[] body, String contentType) {
        return stripCommonFolder(read(body, contentType));
    }

    public Model.Version store(Map<String, byte[]> files) {
        return store(files, null, true);
    }

    private Model.Version store(Map<String, byte[]> files, String root, boolean server) {
        if (files.isEmpty()) {
            throw ApiException.validation("the project has no files");
        }
        List<String> roots;
        if (server) {
            roots = files.keySet().stream().filter(p -> !p.contains("/") && p.endsWith(".mcpgp.yaml")).toList();
            if (roots.size() != 1) {
                throw ApiException.validation("the project needs exactly one *.mcpgp.yaml at its top level (found " + roots.size() + ")");
            }
        } else if (root != null) {
            if (!files.containsKey(root)) {
                throw ApiException.validation("root " + root + " is not one of the files");
            }
            roots = List.of(root);
        } else if (files.size() == 1) {
            roots = List.copyOf(files.keySet());
        } else {
            throw ApiException.validation("an item with several files needs ?root=<path> (the file references point to)");
        }
        Map<String, String> blobs = new TreeMap<>();
        files.forEach((path, content) -> blobs.put(path, store.putBlob(content)));
        StringBuilder index = new StringBuilder();
        blobs.forEach((path, blob) -> index.append(path).append('\0').append(blob).append('\n'));
        Model.Version version = new Model.Version(FileStore.sha256(index.toString().getBytes(StandardCharsets.UTF_8)), roots.get(0), blobs);
        store.saveVersion(version);
        return version;
    }

    private static Map<String, byte[]> unzip(byte[] body) {
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX/") || entry.getName().endsWith("/.DS_Store")
                        || entry.getName().equals(".DS_Store")) {
                    continue;
                }
                byte[] content = zip.readNBytes(MAX_FILE + 1);
                if (content.length > MAX_FILE) {
                    throw ApiException.validation(entry.getName() + " is larger than " + MAX_FILE / 1024 / 1024 + " MB");
                }
                total += content.length;
                add(files, entry.getName(), content, total);
            }
        } catch (IOException e) {
            throw ApiException.validation("not a valid zip");
        }
        return stripCommonFolder(files);
    }

    private static Map<String, byte[]> fromJson(byte[] body) {
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (IOException e) {
            throw ApiException.validation("the body is not valid JSON");
        }
        if (node == null || !node.path("files").isObject()) {
            throw ApiException.validation("expected { \"files\": { \"<path>\": \"<content>\" } } or a zip");
        }
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        var fields = node.get("files").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isTextual()) {
                throw ApiException.validation(field.getKey() + ": file content must be text");
            }
            byte[] content = field.getValue().asText().getBytes(StandardCharsets.UTF_8);
            total += content.length;
            add(files, field.getKey(), content, total);
        }
        return files;
    }

    private static void add(Map<String, byte[]> files, String rawPath, byte[] content, long total) {
        String path = clean(rawPath);
        if (content.length > MAX_FILE) {
            throw ApiException.validation(path + " is larger than " + MAX_FILE / 1024 / 1024 + " MB");
        }
        if (total > MAX_PROJECT) {
            throw ApiException.validation("the project is larger than " + MAX_PROJECT / 1024 / 1024 + " MB");
        }
        if (files.size() >= MAX_FILES) {
            throw ApiException.validation("the project has more than " + MAX_FILES + " files");
        }
        if (files.put(path, content) != null) {
            throw ApiException.validation(path + " appears twice");
        }
    }

    /** Relative, '/'-separated, inside the project: no absolute paths, no '..', no backslashes or NUL. */
    static String clean(String path) {
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*") || p.contains("\0") || p.isBlank()) {
            throw ApiException.validation("invalid path " + path);
        }
        for (String segment : p.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                throw ApiException.validation("invalid path " + path + " (must stay inside the project)");
            }
        }
        return p;
    }

    /** A zip of a folder ("orders/orders.mcpgp.yaml", …) stores the folder's contents. */
    static Map<String, byte[]> stripCommonFolder(Map<String, byte[]> files) {
        String first = files.keySet().stream().findFirst().orElse("");
        int slash = first.indexOf('/');
        if (slash < 0) {
            return files;
        }
        String prefix = first.substring(0, slash + 1);
        if (!files.keySet().stream().allMatch(p -> p.startsWith(prefix))) {
            return files;
        }
        Map<String, byte[]> stripped = new TreeMap<>();
        files.forEach((p, c) -> stripped.put(p.substring(prefix.length()), c));
        return stripped;
    }
}
