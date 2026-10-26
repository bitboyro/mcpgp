package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedFieldsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    final MaskedFields masked = new MaskedFields(List.of("iban", "PaymentToken"));

    @Test
    void namedFieldsAreMaskedAtAnyDepthWhateverTheirCase() throws Exception {
        JsonNode out = masked.mask(JSON.readTree("""
                { "name": "Ana", "Password": "p", "account": { "iban": "RO49", "owner": { "apiKey": 42 } },
                  "cards": [ { "paymentToken": "tok", "last4": "1234" } ], "token": null, "secret": { "nested": true } }
                """));
        assertThat(out.path("name").asText()).isEqualTo("Ana");
        assertThat(out.path("Password").asText()).isEqualTo("***");
        assertThat(out.at("/account/iban").asText()).isEqualTo("***");
        assertThat(out.at("/account/owner/apiKey").asText()).isEqualTo("***");
        assertThat(out.at("/cards/0/paymentToken").asText()).isEqualTo("***");
        assertThat(out.at("/cards/0/last4").asText()).isEqualTo("1234");
        assertThat(out.path("token").isNull()).isTrue();                     // nothing to hide
        assertThat(out.path("secret").asText()).isEqualTo("***");            // a whole object, masked
    }

    @Test
    void untouchedJsonKeepsItsTextAndCutOffJsonIsMaskedPairByPair() {
        String plain = "{ \"name\" :  \"Ana\" }";
        assertThat(masked.maskText(plain)).isSameAs(plain);
        String cut = "[{\"id\":1,\"password\":\"p\\\"q\",\"iban\":\"RO49\",\"note\":\"tok";
        assertThat(masked.maskText(cut)).isEqualTo("[{\"id\":1,\"password\":\"***\",\"iban\":\"***\",\"note\":\"tok");
        assertThat(masked.maskText("token=abc in plain text")).isEqualTo("token=abc in plain text");
    }

    @Test
    void theDefaultsAlwaysApply() {
        assertThat(new MaskedFields(List.of()).names()).containsExactlyInAnyOrder("password", "secret", "token", "apikey", "authorization");
    }
}
