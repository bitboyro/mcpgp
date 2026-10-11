package ro.bitboy.mcpgp.platform.store;

/** The keys both stores accept: checked before any read or write, so both stores refuse the same input. */
final class StoreKeys {

    private StoreKeys() {
    }

    static String serverId(String id) {
        if (!id.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("invalid server id " + id);
        }
        return id;
    }

    static void libraryItem(String kind, String name) {
        if (!kind.matches("[a-z]{2,20}") || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException("invalid library item");
        }
    }

    static String digest(String digest) {
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid digest");
        }
        return digest;
    }

    static String buildId(String id) {
        if (!id.matches("b-[0-9a-zA-Z]{6,40}")) {
            throw new IllegalArgumentException("invalid build id");
        }
        return id;
    }

    static String artifactName(String name) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,200}")) {
            throw new IllegalArgumentException("invalid artifact name");
        }
        return name;
    }

    static void cacheKey(String kind, String key) {
        if (!kind.matches("[a-z-]+") || !key.matches("[0-9a-z.-]+")) {
            throw new IllegalArgumentException("invalid cache key");
        }
    }
}
