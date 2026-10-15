package ro.bitboy.mcpgp.runtime.config;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ro.bitboy.mcpgp.runtime.manifest.Manifest;
import ro.bitboy.mcpgp.schemas.Schemas;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Server settings runtime-config describes but this version doesn't do: startup refuses them, naming each. */
class UnsupportedServerSettingsTest {

    static final Manifest ORDERS = Manifest.file(Path.of("..").toAbsolutePath().normalize().resolve("examples/manifest/orders-mcp.manifest.json"));

    @TempDir
    Path dir;

    RuntimeConfig load(String server) throws Exception {
        Path file = dir.resolve("orders-mcp.yaml");
        Files.writeString(file, "server: { transport: http, port: 8080, " + server + " }\ninstances: [ { id: prod, baseUrl: orders.example } ]\n");
        return new ConfigLoader(new Schemas()).load(ORDERS, new ConfigLoader.Options(Optional.of(file), dir, Map.of(),
                Map.of("storage.root", dir.resolve("data").toString())));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "/server/tls | 'tls: { cert: { path: c.pem }, key: { path: k.pem } }' | serving HTTPS itself isn't supported yet",
            "/server/trustProxy | 'trustProxy: [10.0.0.0/8]' | isn't supported yet",
            "/server/outboundProxy | 'outboundProxy: http://proxy.acme:3128' | isn't supported yet",
    })
    void isRefusedAtStartupWithItsName(String pointer, String yaml, String message) {
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(ConfigException.class).hasMessageContaining(pointer).hasMessageContaining(message);
    }

    @org.junit.jupiter.api.Test
    void tlsByAProxyIsFine() throws Exception {
        assertThat(load("tls: proxy").server().transport()).isEqualTo("http");
    }
}
