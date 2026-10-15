package ro.bitboy.mcpgp.packager;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The package's configuration docs say how each target server's API version is chosen (runtime-config §4.4). */
class ApiVersionsDocsTest {

    static String configuration(ObjectNode manifest) throws Exception {
        return new Docs(manifest, ConfigSchema.generate(manifest), false).generate(true).get("docs/CONFIGURATION.md");
    }

    @Test
    void withSeveralVersionsTheDocsSayHowEachTargetsVersionIsChosen() throws Exception {
        String docs = configuration(DeliveryTest.orders());
        assertThat(docs).contains("## Versions of the").contains("versions 1.0, 2.0 of the API (default 2.0)")
                .contains("asks the target which version it runs (`GET /version` under each version's base path)")
                .contains("it uses the default, 2.0").contains("`prefer: \"1.0\"`").contains("warns when one set to a version runs another");
    }

    @Test
    void withoutADetectionRuleTheDocsSayToSetTheVersion() throws Exception {
        ObjectNode manifest = DeliveryTest.orders();
        ((ObjectNode) manifest.at("/apis/0")).remove("detect");
        assertThat(configuration(manifest)).contains("A target that runs another version needs that version set");
    }

    @Test
    void withOneVersionThereIsNothingToChoose() throws Exception {
        ObjectNode manifest = DeliveryTest.orders();
        ((ArrayNode) manifest.at("/apis/0/contracts")).remove(0);
        assertThat(configuration(manifest)).doesNotContain("## Versions of the");
    }
}
