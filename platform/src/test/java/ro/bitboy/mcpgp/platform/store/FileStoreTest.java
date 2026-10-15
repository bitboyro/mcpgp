package ro.bitboy.mcpgp.platform.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class FileStoreTest extends StoreContract {

    @TempDir
    Path dir;
    Store store;

    @BeforeEach
    void open() {
        store = new FileStore(dir.resolve("store"));
    }

    @Override
    Store store() {
        return store;
    }
}
