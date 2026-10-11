package ro.bitboy.mcpgp.platform.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;

/**
 * The PostgreSQL store (spec/platform-api.md §7.1): the same records as the file store, as JSON in
 * a few tables of one schema ({@code json}, not {@code jsonb}: records keep their key order, which
 * matters for a version's file list), and file contents (blobs, build artifacts, logs) as {@code bytea}.
 * Back up with the database's own tools ({@code pg_dump}). One platform process per schema: the
 * store holds an advisory lock for as long as it is open, and a second process is refused.
 *
 * <p>Optimistic concurrency works as in the file store: a save with a stale revision changes no row
 * and fails with {@link ConflictException}.
 */
public final class PostgresStore implements Store, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** The tables, created when missing; the format row says which store format they hold. */
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS store_info (key text PRIMARY KEY, value text NOT NULL)",
            "CREATE TABLE IF NOT EXISTS servers (id text PRIMARY KEY, revision bigint NOT NULL, doc json NOT NULL)",
            "CREATE TABLE IF NOT EXISTS library (kind text, name text, revision bigint NOT NULL, doc json NOT NULL, PRIMARY KEY (kind, name))",
            "CREATE TABLE IF NOT EXISTS blobs (digest text PRIMARY KEY, content bytea NOT NULL)",
            "CREATE TABLE IF NOT EXISTS versions (digest text PRIMARY KEY, doc json NOT NULL)",
            "CREATE TABLE IF NOT EXISTS builds (id text PRIMARY KEY, doc json NOT NULL)",
            "CREATE TABLE IF NOT EXISTS build_files (build text, name text, content bytea NOT NULL, PRIMARY KEY (build, name))",
            "CREATE TABLE IF NOT EXISTS build_logs (build text PRIMARY KEY, text text NOT NULL)",
            // identities, tokens, credentials, approvals: seq keeps the file store's order (a save moves a record to the end)
            "CREATE SEQUENCE IF NOT EXISTS records_seq",
            "CREATE TABLE IF NOT EXISTS records (kind text, id text, seq bigint NOT NULL, doc json NOT NULL, PRIMARY KEY (kind, id))",
            "CREATE TABLE IF NOT EXISTS activity (seq bigserial PRIMARY KEY, doc json NOT NULL)",
            "CREATE TABLE IF NOT EXISTS cache (kind text, key text, content bytea NOT NULL, PRIMARY KEY (kind, key))");

    private final HikariDataSource pool;
    private final Connection lock;
    private final String schema;

    /**
     * Opens (or creates) the store in {@code schema}; refuses a store of another format and a schema
     * another platform process has open.
     */
    public PostgresStore(String url, String user, String password, String schema, int poolSize) {
        if (!schema.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("storage.schema: lowercase letters, digits and '_'");
        }
        this.schema = schema;
        try {
            Properties props = new Properties();
            if (user != null) {
                props.setProperty("user", user);
            }
            if (password != null) {
                props.setProperty("password", password);
            }
            props.setProperty("ApplicationName", "mcpgp-platform");
            this.lock = DriverManager.getConnection(url, props);
            try (Statement st = lock.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            }
            try (PreparedStatement st = lock.prepareStatement("SELECT pg_try_advisory_lock(hashtext(?))")) {
                st.setString(1, "mcpgp-platform:" + schema);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) {
                        lock.close();
                        throw new IllegalStateException("Another platform process has the store in schema " + schema
                                + " open; run one platform process per store");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("The PostgreSQL store at " + url + " can't be opened: " + e.getMessage(), e);
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setSchema(schema);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("mcpgp-store");
        config.addDataSourceProperty("ApplicationName", "mcpgp-platform");
        this.pool = new HikariDataSource(config);
        try {
            open();
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    private void open() {
        transaction(c -> {
            try (Statement st = c.createStatement()) {
                for (String ddl : SCHEMA) {
                    st.execute(ddl);
                }
            }
            String format = one(c, "SELECT value FROM store_info WHERE key = 'format'", ps -> { }, rs -> rs.getString(1)).orElse(null);
            if (format == null) {
                if (one(c, "SELECT count(*) FROM servers", ps -> { }, rs -> rs.getLong(1)).orElse(0L) > 0) {
                    throw new IllegalStateException("schema " + schema + " has platform tables but no store format; choose an empty schema");
                }
                update(c, "INSERT INTO store_info (key, value) VALUES ('format', ?)", ps -> ps.setString(1, FORMAT));
            } else if (!format.equals(FORMAT)) {
                throw new IllegalStateException("The store in schema " + schema + " has format " + format + "; this platform release uses "
                        + FORMAT + ". Before 1.0.0 stores are not migrated: export the data with the release that wrote it "
                        + "(GET /api/v1/export) and import it here (POST /api/v1/import).");
            }
            return null;
        });
    }

    @Override
    public void close() {
        pool.close();
        try {
            lock.close();       // ends the session, which releases the advisory lock
        } catch (SQLException e) {
            // closing anyway
        }
    }

    @Override
    public void check() {
        query("SELECT 1", ps -> { }, rs -> rs.getInt(1));
    }

    @Override
    public String describe() {
        return "postgres (schema " + schema + ")";
    }

    // ---------------------------------------------------------------------- servers

    @Override
    public List<Model.Server> servers() {
        return query("SELECT doc FROM servers ORDER BY id", ps -> { }, rs -> read(rs, Model.Server.class));
    }

    @Override
    public Optional<Model.Server> server(String id) {
        StoreKeys.serverId(id);
        return first("SELECT doc FROM servers WHERE id = ?", ps -> ps.setString(1, id), rs -> read(rs, Model.Server.class));
    }

    @Override
    public Model.Server saveServer(Model.Server server, long expectedRevision) {
        StoreKeys.serverId(server.id());
        long revision = expectedRevision < 0 ? 1 : expectedRevision + 1;
        Model.Server saved = new Model.Server(server.id(), server.title(), server.parent(), server.archived(), server.created(),
                server.updated(), List.copyOf(server.versions()), revision);
        int changed = expectedRevision < 0
                ? update("INSERT INTO servers (id, revision, doc) VALUES (?, ?, ?::json) ON CONFLICT DO NOTHING",
                        ps -> { ps.setString(1, server.id()); ps.setLong(2, revision); ps.setString(3, json(saved)); })
                : update("UPDATE servers SET revision = ?, doc = ?::json WHERE id = ? AND revision = ?",
                        ps -> { ps.setLong(1, revision); ps.setString(2, json(saved)); ps.setString(3, server.id()); ps.setLong(4, expectedRevision); });
        if (changed == 0) {
            throw new ConflictException("server " + server.id() + (expectedRevision < 0 ? " exists" : " was changed meanwhile"));
        }
        return saved;
    }

    // ---------------------------------------------------------------------- library

    @Override
    public List<Model.LibraryItem> library() {
        return query("SELECT doc FROM library ORDER BY kind, name", ps -> { }, rs -> read(rs, Model.LibraryItem.class));
    }

    @Override
    public Optional<Model.LibraryItem> libraryItem(String kind, String name) {
        StoreKeys.libraryItem(kind, name);
        return first("SELECT doc FROM library WHERE kind = ? AND name = ?", ps -> { ps.setString(1, kind); ps.setString(2, name); },
                rs -> read(rs, Model.LibraryItem.class));
    }

    @Override
    public Model.LibraryItem saveLibraryItem(Model.LibraryItem item, long expectedRevision) {
        StoreKeys.libraryItem(item.kind(), item.name());
        long revision = expectedRevision < 0 ? 1 : expectedRevision + 1;
        Model.LibraryItem saved = new Model.LibraryItem(item.kind(), item.name(), item.title(), item.created(), item.updated(),
                List.copyOf(item.versions()), revision);
        int changed = expectedRevision < 0
                ? update("INSERT INTO library (kind, name, revision, doc) VALUES (?, ?, ?, ?::json) ON CONFLICT DO NOTHING",
                        ps -> { ps.setString(1, item.kind()); ps.setString(2, item.name()); ps.setLong(3, revision); ps.setString(4, json(saved)); })
                : update("UPDATE library SET revision = ?, doc = ?::json WHERE kind = ? AND name = ? AND revision = ?",
                        ps -> { ps.setLong(1, revision); ps.setString(2, json(saved)); ps.setString(3, item.kind()); ps.setString(4, item.name());
                            ps.setLong(5, expectedRevision); });
        if (changed == 0) {
            throw new ConflictException(item.kind() + "/" + item.name() + (expectedRevision < 0 ? " exists" : " was changed meanwhile"));
        }
        return saved;
    }

    // ---------------------------------------------------------------------- content

    @Override
    public String putBlob(byte[] content) {
        String digest = FileStore.sha256(content);
        update("INSERT INTO blobs (digest, content) VALUES (?, ?) ON CONFLICT DO NOTHING", ps -> { ps.setString(1, digest); ps.setBytes(2, content); });
        return digest;
    }

    @Override
    public byte[] blob(String digest) {
        StoreKeys.digest(digest);
        return first("SELECT content FROM blobs WHERE digest = ?", ps -> ps.setString(1, digest), rs -> rs.getBytes(1))
                .orElseThrow(() -> new UncheckedIOException(new IOException("blob " + digest + " is missing")));
    }

    @Override
    public void saveVersion(Model.Version version) {
        StoreKeys.digest(version.digest());
        update("INSERT INTO versions (digest, doc) VALUES (?, ?::json) ON CONFLICT DO NOTHING",
                ps -> { ps.setString(1, version.digest()); ps.setString(2, json(version)); });
    }

    @Override
    public Optional<Model.Version> version(String digest) {
        StoreKeys.digest(digest);
        return first("SELECT doc FROM versions WHERE digest = ?", ps -> ps.setString(1, digest), rs -> read(rs, Model.Version.class));
    }

    // ---------------------------------------------------------------------- builds

    @Override
    public List<Model.Build> builds() {
        return query("SELECT doc FROM builds ORDER BY id", ps -> { }, rs -> read(rs, Model.Build.class));
    }

    @Override
    public Optional<Model.Build> build(String id) {
        StoreKeys.buildId(id);
        return first("SELECT doc FROM builds WHERE id = ?", ps -> ps.setString(1, id), rs -> read(rs, Model.Build.class));
    }

    @Override
    public void saveBuild(Model.Build build) {
        StoreKeys.buildId(build.id());
        update("INSERT INTO builds (id, doc) VALUES (?, ?::json) ON CONFLICT (id) DO UPDATE SET doc = EXCLUDED.doc",
                ps -> { ps.setString(1, build.id()); ps.setString(2, json(build)); });
    }

    @Override
    public void writeBuildFile(String build, String name, byte[] content) {
        StoreKeys.buildId(build);
        StoreKeys.artifactName(name);
        update("INSERT INTO build_files (build, name, content) VALUES (?, ?, ?) ON CONFLICT (build, name) DO UPDATE SET content = EXCLUDED.content",
                ps -> { ps.setString(1, build); ps.setString(2, name); ps.setBytes(3, content); });
    }

    @Override
    public void appendBuildLog(String build, String text) {
        StoreKeys.buildId(build);
        update("INSERT INTO build_logs (build, text) VALUES (?, ?) ON CONFLICT (build) DO UPDATE SET text = build_logs.text || EXCLUDED.text",
                ps -> { ps.setString(1, build); ps.setString(2, text); });
    }

    @Override
    public Optional<byte[]> buildFile(String build, String name) {
        StoreKeys.buildId(build);
        if (name.equals("log.txt")) {
            return first("SELECT text FROM build_logs WHERE build = ?", ps -> ps.setString(1, build),
                    rs -> rs.getString(1).getBytes(StandardCharsets.UTF_8));
        }
        StoreKeys.artifactName(name);
        return first("SELECT content FROM build_files WHERE build = ? AND name = ?", ps -> { ps.setString(1, build); ps.setString(2, name); },
                rs -> rs.getBytes(1));
    }

    @Override
    public void deleteBuildFiles(String build) {
        StoreKeys.buildId(build);
        transaction(c -> {
            update(c, "DELETE FROM build_files WHERE build = ?", ps -> ps.setString(1, build));
            update(c, "DELETE FROM build_logs WHERE build = ?", ps -> ps.setString(1, build));
            return null;
        });
    }

    // ---------------------------------------------------------------------- identities, accounts, approvals

    @Override
    public List<Model.Identity> identities() {
        return records("identity", Model.Identity.class);
    }

    @Override
    public void saveIdentity(Model.Identity identity) {
        saveRecord("identity", identity.id(), identity);
    }

    @Override
    public List<Model.Token> tokens() {
        return records("token", Model.Token.class);
    }

    @Override
    public void saveToken(Model.Token token) {
        saveRecord("token", token.id(), token);
    }

    @Override
    public List<Model.Credential> credentials() {
        return records("credential", Model.Credential.class);
    }

    @Override
    public void saveCredential(Model.Credential credential) {
        saveRecord("credential", credential.identity(), credential);
    }

    @Override
    public List<Model.Approval> approvals() {
        return records("approval", Model.Approval.class);
    }

    @Override
    public void saveApproval(Model.Approval approval) {
        saveRecord("approval", approval.id(), approval);
    }

    private <T> List<T> records(String kind, Class<T> type) {
        return query("SELECT doc FROM records WHERE kind = ? ORDER BY seq", ps -> ps.setString(1, kind), rs -> read(rs, type));
    }

    private void saveRecord(String kind, String id, Object record) {
        update("INSERT INTO records (kind, id, seq, doc) VALUES (?, ?, nextval('records_seq'), ?::json) "
                        + "ON CONFLICT (kind, id) DO UPDATE SET seq = nextval('records_seq'), doc = EXCLUDED.doc",
                ps -> { ps.setString(1, kind); ps.setString(2, id); ps.setString(3, json(record)); });
    }

    // ---------------------------------------------------------------------- activity

    @Override
    public void append(Model.Activity activity) {
        update("INSERT INTO activity (doc) VALUES (?::json)", ps -> ps.setString(1, json(activity)));
    }

    @Override
    public List<Model.Activity> activity(Predicate<Model.Activity> filter, int limit) {
        // newest first, read in pages until enough entries pass the filter
        return transaction(c -> {
            List<Model.Activity> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT doc FROM activity ORDER BY seq DESC")) {
                ps.setFetchSize(500);
                try (ResultSet rs = ps.executeQuery()) {
                    while (result.size() < limit && rs.next()) {
                        Model.Activity entry = read(rs, Model.Activity.class);
                        if (filter.test(entry)) {
                            result.add(entry);
                        }
                    }
                }
            }
            return result;
        });
    }

    // ---------------------------------------------------------------------- cache

    @Override
    public Optional<byte[]> cached(String kind, String key) {
        StoreKeys.cacheKey(kind, key);
        return first("SELECT content FROM cache WHERE kind = ? AND key = ?", ps -> { ps.setString(1, kind); ps.setString(2, key); },
                rs -> rs.getBytes(1));
    }

    @Override
    public void cache(String kind, String key, byte[] content) {
        StoreKeys.cacheKey(kind, key);
        update("INSERT INTO cache (kind, key, content) VALUES (?, ?, ?) ON CONFLICT (kind, key) DO UPDATE SET content = EXCLUDED.content",
                ps -> { ps.setString(1, kind); ps.setString(2, key); ps.setBytes(3, content); });
    }

    // ---------------------------------------------------------------------- JDBC

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T transaction(Work<T> work) {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("store: " + e.getMessage(), e);
        }
    }

    private <T> List<T> query(String sql, Binder binder, Row<T> row) {
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            List<T> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(row.map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("store: " + e.getMessage(), e);
        }
    }

    private <T> Optional<T> first(String sql, Binder binder, Row<T> row) {
        return query(sql, binder, row).stream().findFirst();
    }

    private static <T> Optional<T> one(Connection c, String sql, Binder binder, Row<T> row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(row.map(rs)) : Optional.empty();
            }
        }
    }

    private int update(String sql, Binder binder) {
        try (Connection c = pool.getConnection()) {
            return update(c, sql, binder);
        } catch (SQLException e) {
            throw new IllegalStateException("store: " + e.getMessage(), e);
        }
    }

    private static int update(Connection c, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        }
    }

    private static <T> T read(ResultSet rs, Class<T> type) throws SQLException {
        try {
            return JSON.readValue(rs.getString(1), type);
        } catch (IOException e) {
            throw new UncheckedIOException("can't read a stored " + type.getSimpleName(), e);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
