package ro.bitboy.mcpgp.platform.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The file store (spec/platform-api.md §7.1): a directory, backed up by copying it. Writes go to a
 * temporary file and are moved into place, so a crash never leaves half a file. One platform process
 * per directory.
 *
 * <pre>
 * root/store.json                 { "format": "0.1" }
 * root/servers/(id)/server.json
 * root/blobs/sha256/(ab)/(digest)
 * root/versions/(digest).json
 * root/identities/identities.json, tokens.json
 * root/activity/activity-YYYY-MM.jsonl
 * root/cache/(kind)/(key)
 * </pre>
 */
public final class FileStore implements Store {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper LINES = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path root;

    /** Opens (or creates) a store; refuses a store of another format (no migrations before 1.0.0). */
    public FileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            ownerOnly(this.root, "rwx------");
            Path marker = this.root.resolve("store.json");
            if (Files.exists(marker)) {
                String format = JSON.readTree(marker.toFile()).path("format").asText("");
                if (!format.equals(FORMAT)) {
                    throw new IllegalStateException("The store at " + this.root + " has format " + format + "; this platform release uses "
                            + FORMAT + ". Before 1.0.0 stores are not migrated: export the data with the release that wrote it "
                            + "(GET /api/v1/export) and import it here (POST /api/v1/import).");
                }
            } else {
                try (Stream<Path> existing = Files.list(this.root)) {
                    if (existing.findAny().isPresent()) {
                        throw new IllegalStateException(this.root + " is not empty and has no store.json; choose an empty directory");
                    }
                }
                write(marker, JSON.writeValueAsBytes(Map.of("format", FORMAT)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The store at " + this.root + " can't be opened", e);
        }
    }

    @Override
    public String describe() {
        return "files (" + root + ")";
    }

    // ---------------------------------------------------------------------- servers

    @Override
    public synchronized List<Model.Server> servers() {
        Path dir = root.resolve("servers");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Model.Server> servers = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                read(entry.resolve("server.json"), Model.Server.class).ifPresent(servers::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return servers;
    }

    @Override
    public synchronized Optional<Model.Server> server(String id) {
        return read(serverFile(id), Model.Server.class);
    }

    @Override
    public synchronized Model.Server saveServer(Model.Server server, long expectedRevision) {
        Optional<Model.Server> current = server(server.id());
        if (expectedRevision < 0 && current.isPresent()) {
            throw new ConflictException("server " + server.id() + " exists");
        }
        if (expectedRevision >= 0 && (current.isEmpty() || current.get().revision() != expectedRevision)) {
            throw new ConflictException("server " + server.id() + " was changed meanwhile");
        }
        Model.Server saved = new Model.Server(server.id(), server.title(), server.parent(), server.archived(), server.created(),
                server.updated(), List.copyOf(server.versions()), current.map(Model.Server::revision).orElse(0L) + 1);
        writeJson(serverFile(server.id()), saved);
        return saved;
    }

    private Path serverFile(String id) {
        return root.resolve("servers").resolve(StoreKeys.serverId(id)).resolve("server.json");
    }

    // ---------------------------------------------------------------------- library

    @Override
    public synchronized List<Model.LibraryItem> library() {
        Path dir = root.resolve("library");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Model.LibraryItem> items = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir, 3)) {
            for (Path file : files.filter(p -> p.getFileName().toString().equals("item.json")).sorted().toList()) {
                read(file, Model.LibraryItem.class).ifPresent(items::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return items;
    }

    @Override
    public synchronized Optional<Model.LibraryItem> libraryItem(String kind, String name) {
        return read(libraryFile(kind, name), Model.LibraryItem.class);
    }

    @Override
    public synchronized Model.LibraryItem saveLibraryItem(Model.LibraryItem item, long expectedRevision) {
        Optional<Model.LibraryItem> current = libraryItem(item.kind(), item.name());
        if (expectedRevision < 0 && current.isPresent()) {
            throw new ConflictException(item.kind() + "/" + item.name() + " exists");
        }
        if (expectedRevision >= 0 && (current.isEmpty() || current.get().revision() != expectedRevision)) {
            throw new ConflictException(item.kind() + "/" + item.name() + " was changed meanwhile");
        }
        Model.LibraryItem saved = new Model.LibraryItem(item.kind(), item.name(), item.title(), item.created(), item.updated(),
                List.copyOf(item.versions()), current.map(Model.LibraryItem::revision).orElse(0L) + 1);
        writeJson(libraryFile(item.kind(), item.name()), saved);
        return saved;
    }

    private Path libraryFile(String kind, String name) {
        StoreKeys.libraryItem(kind, name);
        return root.resolve("library").resolve(kind).resolve(name).resolve("item.json");
    }

    // ---------------------------------------------------------------------- content

    @Override
    public String putBlob(byte[] content) {
        String digest = sha256(content);
        Path file = blobFile(digest);
        if (!Files.exists(file)) {
            write(file, content);
        }
        return digest;
    }

    @Override
    public byte[] blob(String digest) {
        try {
            return Files.readAllBytes(blobFile(digest));
        } catch (IOException e) {
            throw new UncheckedIOException("blob " + digest + " is missing", e);
        }
    }

    private Path blobFile(String digest) {
        StoreKeys.digest(digest);
        return root.resolve("blobs/sha256").resolve(digest.substring(0, 2)).resolve(digest);
    }

    @Override
    public void saveVersion(Model.Version version) {
        Path file = versionFile(version.digest());
        if (!Files.exists(file)) {
            writeJson(file, version);
        }
    }

    @Override
    public Optional<Model.Version> version(String digest) {
        return read(versionFile(digest), Model.Version.class);
    }

    private Path versionFile(String digest) {
        StoreKeys.digest(digest);
        return root.resolve("versions").resolve(digest + ".json");
    }

    // ---------------------------------------------------------------------- builds

    @Override
    public synchronized List<Model.Build> builds() {
        Path dir = root.resolve("builds");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Model.Build> builds = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                read(entry.resolve("build.json"), Model.Build.class).ifPresent(builds::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return builds;
    }

    @Override
    public synchronized Optional<Model.Build> build(String id) {
        return read(buildDir(id).resolve("build.json"), Model.Build.class);
    }

    @Override
    public synchronized void saveBuild(Model.Build build) {
        writeJson(buildDir(build.id()).resolve("build.json"), build);
    }

    @Override
    public void writeBuildFile(String build, String name, byte[] content) {
        write(buildFilePath(build, name), content);
    }

    @Override
    public synchronized void appendBuildLog(String build, String text) {
        Path file = buildDir(build).resolve("log.txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> buildFile(String build, String name) {
        Path file = name.equals("log.txt") ? buildDir(build).resolve("log.txt") : buildFilePath(build, name);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void deleteBuildFiles(String build) {
        for (Path file : List.of(buildDir(build).resolve("artifacts"), buildDir(build).resolve("log.txt"))) {
            if (!Files.exists(file)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(file)) {
                for (Path p : paths.sorted(Collections.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private Path buildDir(String id) {
        return root.resolve("builds").resolve(StoreKeys.buildId(id));
    }

    private Path buildFilePath(String build, String name) {
        return buildDir(build).resolve("artifacts").resolve(StoreKeys.artifactName(name));
    }

    // ---------------------------------------------------------------------- identities

    @Override
    public synchronized List<Model.Identity> identities() {
        return readList(root.resolve("identities/identities.json"), new TypeReference<>() {
        });
    }

    @Override
    public synchronized void saveIdentity(Model.Identity identity) {
        List<Model.Identity> all = new ArrayList<>(identities());
        all.removeIf(i -> i.id().equals(identity.id()));
        all.add(identity);
        writeJson(root.resolve("identities/identities.json"), all);
    }

    @Override
    public synchronized List<Model.Token> tokens() {
        return readList(root.resolve("identities/tokens.json"), new TypeReference<>() {
        });
    }

    @Override
    public synchronized void saveToken(Model.Token token) {
        List<Model.Token> all = new ArrayList<>(tokens());
        all.removeIf(t -> t.id().equals(token.id()));
        all.add(token);
        writeJson(root.resolve("identities/tokens.json"), all);
    }

    // ---------------------------------------------------------------------- accounts and approvals

    @Override
    public synchronized List<Model.Credential> credentials() {
        return readList(root.resolve("identities/credentials.json"), new TypeReference<>() {
        });
    }

    @Override
    public synchronized void saveCredential(Model.Credential credential) {
        List<Model.Credential> all = new ArrayList<>(credentials());
        all.removeIf(c -> c.identity().equals(credential.identity()));
        all.add(credential);
        writeJson(root.resolve("identities/credentials.json"), all);
        ownerOnly(root.resolve("identities/credentials.json"), "rw-------");
    }

    @Override
    public synchronized List<Model.Approval> approvals() {
        return readList(root.resolve("approvals/approvals.json"), new TypeReference<>() {
        });
    }

    @Override
    public synchronized void saveApproval(Model.Approval approval) {
        List<Model.Approval> all = new ArrayList<>(approvals());
        all.removeIf(a -> a.id().equals(approval.id()));
        all.add(approval);
        writeJson(root.resolve("approvals/approvals.json"), all);
    }

    // ---------------------------------------------------------------------- activity

    @Override
    public synchronized void append(Model.Activity activity) {
        Path file = root.resolve("activity").resolve("activity-" + YearMonth.from(java.time.Instant.parse(activity.ts()).atZone(ZoneOffset.UTC)) + ".jsonl");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, LINES.writeValueAsString(activity) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("the activity log can't be written", e);
        }
    }

    @Override
    public synchronized List<Model.Activity> activity(Predicate<Model.Activity> filter, int limit) {
        Path dir = root.resolve("activity");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Model.Activity> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted(Collections.reverseOrder()).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
                    Model.Activity entry = LINES.readValue(lines.get(i), Model.Activity.class);
                    if (filter.test(entry)) {
                        result.add(entry);
                    }
                }
                if (result.size() >= limit) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    // ---------------------------------------------------------------------- cache

    @Override
    public Optional<byte[]> cached(String kind, String key) {
        Path file = cacheFile(kind, key);
        try {
            return Files.exists(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void cache(String kind, String key, byte[] content) {
        write(cacheFile(kind, key), content);
    }

    @Override
    public int pruneCache(java.util.function.Predicate<String> keep) {
        Path cache = root.resolve("cache");
        if (!Files.isDirectory(cache)) {
            return 0;
        }
        int removed = 0;
        try (java.util.stream.Stream<Path> files = Files.walk(cache, 2)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!keep.test(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return removed;
    }

    private Path cacheFile(String kind, String key) {
        StoreKeys.cacheKey(kind, key);
        return root.resolve("cache").resolve(kind).resolve(key);
    }

    // ---------------------------------------------------------------------- files

    private <T> Optional<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(file.toFile(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("can't read " + root.relativize(file), e);
        }
    }

    private <T> List<T> readList(Path file, TypeReference<List<T>> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return JSON.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("can't read " + root.relativize(file), e);
        }
    }

    private void writeJson(Path file, Object value) {
        try {
            write(file, JSON.writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Write to a temporary file next to the target, then move it into place. */
    private static void write(Path file, byte[] content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), ".tmp-", "");
            Files.write(temp, content);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("can't write " + file, e);
        }
    }

    public static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ownerOnly(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (IOException | UnsupportedOperationException e) {
            // not a POSIX file system
        }
    }
}
