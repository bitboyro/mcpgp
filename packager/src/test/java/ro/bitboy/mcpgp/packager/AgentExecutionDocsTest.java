package ro.bitboy.mcpgp.packager;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionDocsTest {
    @Test void guidanceFollowsEnabledExecutionCapabilities() throws Exception {
        var manifest = DeliveryTest.orders();
        String basic = new Docs(manifest, ConfigSchema.generate(manifest), false).generate(true).get("AGENTS.md");
        assertThat(basic).doesNotContain("Use `batch_call`", "inspect them with `result_get`", "Use `session_policy`");
        manifest.withObject("policies").withObject("batch").put("enabled", true);
        manifest.withObject("policies").withObject("results").put("mode", "auto");
        manifest.withObject("policies").withObject("runtimeDefaults").withObject("configuration").withObject("sessions").put("enabled", true);
        String enabled = new Docs(manifest, ConfigSchema.generate(manifest), false).generate(true).get("AGENTS.md");
        assertThat(enabled).contains("Use `batch_call`", "unknown outcome", "Receipts expire", "inspect them with `result_get`", "never rerun a write", "Use `session_policy`", "Deployment credentials");
    }
}
