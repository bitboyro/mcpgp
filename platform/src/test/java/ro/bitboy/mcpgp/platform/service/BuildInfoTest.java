package ro.bitboy.mcpgp.platform.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuildInfoTest {

    @Test
    void theCliImageIsTheSettingElseThePublishedPlatformImagesSibling() {
        assertThat(BuildInfo.cliImage(Map.of("MCPGP_CLI_IMAGE", " registry.acme/tools/mcpgp-cli:0.4.0 "))).isEqualTo("registry.acme/tools/mcpgp-cli:0.4.0");
        assertThat(BuildInfo.cliImage(Map.of("MCPGP_PLATFORM_IMAGE", "docker.io/acme/mcpgp-platform:0.4.0"))).isEqualTo("docker.io/acme/mcpgp-cli:0.4.0");
        assertThat(BuildInfo.cliImage(Map.of("MCPGP_PLATFORM_IMAGE", "registry:5000/acme/mcpgp-platform"))).isEqualTo("registry:5000/acme/mcpgp-cli");
        // built locally: no registry to point at
        assertThat(BuildInfo.cliImage(Map.of("MCPGP_PLATFORM_IMAGE", "mcpgp-platform:local"))).isNull();
        assertThat(BuildInfo.cliImage(Map.of())).isNull();
    }
}
