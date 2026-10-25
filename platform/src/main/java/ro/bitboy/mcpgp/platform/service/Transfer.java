package ro.bitboy.mcpgp.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ro.bitboy.mcpgp.platform.api.ApiException;
import ro.bitboy.mcpgp.platform.store.FileStore;
import ro.bitboy.mcpgp.platform.store.Model;
import ro.bitboy.mcpgp.platform.store.Store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Export / import (spec/platform-api.md §7.1): how data moves between platform releases before 1.0.0,
 * when stores are not migrated. A zip with {@code export.json} (every record, with its store format)
 * and the content blobs; build artifacts only on request. Import fills a fresh store; identities that
 * exist already (the new installation's administrator) are kept.
 */
public final class Transfer {

    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Store store;

    public Transfer(Store store) {
        this.store = store;
    }

    public byte[] export(boolean artifacts) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("format", Store.FORMAT);
        data.put("exportedAt", Instant.now().toString());
        data.put("servers", store.servers());
        data.put("library", store.library());
        Set<String> digests = new LinkedHashSet<>();
        store.servers().forEach(s -> s.versions().forEach(v -> digests.add(v.digest())));
        store.library().forEach(i -> i.versions().forEach(v -> digests.add(v.digest())));
        List<Model.Version> versions = digests.stream().map(d -> store.version(d).orElseThrow()).toList();
        data.put("versions", versions);
        data.put("builds", store.builds());
        data.put("identities", store.identities());
        data.put("tokens", store.tokens());
        data.put("credentials", store.credentials());
        data.put("approvals", store.approvals());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "export.json", JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
            Set<String> blobs = new LinkedHashSet<>();
            versions.forEach(v -> blobs.addAll(v.files().values()));
            for (String blob : blobs) {
                put(zip, "blobs/" + blob, store.blob(blob));
            }
            if (artifacts) {
                for (Model.Build build : store.builds()) {
                    for (Model.Artifact artifact : build.artifacts()) {
                        var content = store.buildFile(build.id(), artifact.name());
                        if (content.isPresent()) {
                            put(zip, "artifacts/" + build.id() + "/" + artifact.name(), content.get());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Imports into a store without servers or library items (the platform's own server and untouched
     * starter examples aside); returns counts. The export's copy of the platform's own server, with its
     * history, replaces the fresh one, and so does its copy of a starter example; the next start brings
     * them to this release.
     */
    public Map<String, Integer> importAll(byte[] zip) {
        if (store.servers().stream().anyMatch(s -> !s.system()) || store.library().stream().anyMatch(i -> !LibraryExamples.untouched(i))) {
            throw new ApiException(409, "CONFLICT", "import fills a fresh store; this one has servers or library items already");
        }
        JsonNode data = null;
        Map<String, byte[]> blobs = new HashMap<>();
        Map<String, byte[]> artifacts = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = in.readAllBytes();
                if (entry.getName().equals("export.json")) {
                    data = JSON.readTree(content);
                } else if (entry.getName().startsWith("blobs/")) {
                    blobs.put(entry.getName().substring("blobs/".length()), content);
                } else if (entry.getName().startsWith("artifacts/")) {
                    artifacts.put(entry.getName().substring("artifacts/".length()), content);
                }
            }
        } catch (IOException e) {
            throw ApiException.validation("not an export zip");
        }
        if (data == null) {
            throw ApiException.validation("the zip has no export.json");
        }
        String format = data.path("format").asText();
        if (!format.equals(Store.FORMAT)) {
            // the place for adapters from older pre-1.0 formats, when there are any
            throw ApiException.validation("export format " + format + " can't be imported by this release (it reads " + Store.FORMAT + ")");
        }
        try {
            for (Map.Entry<String, byte[]> blob : blobs.entrySet()) {
                if (!FileStore.sha256(blob.getValue()).equals(blob.getKey())) {
                    throw ApiException.validation("blob " + blob.getKey() + " doesn't match its digest");
                }
                store.putBlob(blob.getValue());
            }
            List<Model.Version> versions = JSON.convertValue(data.path("versions"), new TypeReference<>() {
            });
            versions.forEach(store::saveVersion);
            List<Model.Server> servers = JSON.convertValue(data.path("servers"), new TypeReference<>() {
            });
            for (Model.Server server : servers) {
                store.saveServer(server, store.server(server.id()).filter(Model.Server::system).map(Model.Server::revision).orElse(-1L));
            }
            List<Model.LibraryItem> library = JSON.convertValue(data.path("library"), new TypeReference<>() {
            });
            library.forEach(i -> store.saveLibraryItem(i, store.libraryItem(i.kind(), i.name())
                    .filter(LibraryExamples::untouched).map(Model.LibraryItem::revision).orElse(-1L)));
            List<Model.Build> builds = JSON.convertValue(data.path("builds"), new TypeReference<>() {
            });
            for (Model.Build build : builds) {
                store.saveBuild(build);
                for (Model.Artifact artifact : build.artifacts()) {
                    byte[] content = artifacts.get(build.id() + "/" + artifact.name());
                    if (content != null) {
                        store.writeBuildFile(build.id(), artifact.name(), content);
                    }
                }
            }
            Set<String> names = new java.util.HashSet<>();
            Set<String> ids = new java.util.HashSet<>();
            store.identities().forEach(i -> {
                names.add(i.name().toLowerCase());
                ids.add(i.id());
            });
            List<Model.Identity> identities = JSON.convertValue(data.path("identities"), new TypeReference<>() {
            });
            Set<String> imported = new java.util.HashSet<>();
            for (Model.Identity identity : identities) {
                if (!names.contains(identity.name().toLowerCase()) && !ids.contains(identity.id())) {
                    store.saveIdentity(identity);
                    imported.add(identity.id());
                }
            }
            List<Model.Token> tokens = JSON.convertValue(data.path("tokens"), new TypeReference<>() {
            });
            tokens.stream().filter(t -> imported.contains(t.identity())).forEach(store::saveToken);
            List<Model.Credential> credentials = JSON.convertValue(data.path("credentials"), new TypeReference<>() {
            });
            credentials.stream().filter(c -> imported.contains(c.identity())).forEach(store::saveCredential);
            List<Model.Approval> approvals = JSON.convertValue(data.path("approvals"), new TypeReference<>() {
            });
            approvals.forEach(store::saveApproval);
            return Map.of("servers", servers.size(), "library", library.size(), "versions", versions.size(), "builds", builds.size(),
                    "identities", imported.size());
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("the export can't be read: " + e.getMessage());
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
