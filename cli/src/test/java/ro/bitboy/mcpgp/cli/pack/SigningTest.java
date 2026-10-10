package ro.bitboy.mcpgp.cli.pack;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BLAKE2b against reference values (RFC 7693 / Python hashlib) and minisign signatures. */
class SigningTest {

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    @Test
    void blake2bMatchesReferenceValues() {
        assertThat(hex(Blake2b.hash(new byte[0], 64))).isEqualTo(
                "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce");
        assertThat(hex(Blake2b.hash("abc".getBytes(StandardCharsets.US_ASCII), 64))).isEqualTo(
                "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923");
        assertThat(hex(Blake2b.hash("abc".getBytes(StandardCharsets.US_ASCII), 32)))
                .isEqualTo("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319");
        // block boundaries: exactly one block, two blocks, several blocks
        assertThat(hex(Blake2b.hash(pattern(128), 64))).isEqualTo(
                "2319e3789c47e2daa5fe807f61bec2a1a6537fa03f19ff32e87eecbfd64b7e0e8ccff439ac333b040f19b0c4ddd11a61e24ac1fe0f10a039806c5dcc0da3d115");
        assertThat(hex(Blake2b.hash(pattern(256), 32))).isEqualTo("582f782226018ec33076bd8d1c42413530ac7e1126260ffc0f306ba3befc3f24");
        assertThat(hex(Blake2b.hash(pattern(300), 64))).isEqualTo(
                "3a482b7748b0bdc43c3d00c080890c10e57a9aa5618f78b86067eb7eaae4942acd96d827accbc16958364ae5b0df6105bbd3b15445092eba1137b5f69c1070f1");
    }

    @Test
    void signaturesVerifyAndCatchChanges() throws Exception {
        Minisign.Keys keys = Minisign.generate();
        Minisign.Keys secret = Minisign.readSecretKey(Minisign.secretKeyFile(keys));
        Minisign.Keys pub = Minisign.readPublicKey(Minisign.publicKeyFile(keys));
        byte[] data = "abc  SHA256SUMS\n".getBytes(StandardCharsets.UTF_8);
        String signature = Minisign.sign(secret, data, "timestamp:1\tfile:SHA256SUMS\thashed");
        assertThat(Minisign.verify(pub, data, signature)).isEqualTo("timestamp:1\tfile:SHA256SUMS\thashed");

        assertThatThrownBy(() -> Minisign.verify(pub, "abd  SHA256SUMS\n".getBytes(StandardCharsets.UTF_8), signature))
                .hasMessageContaining("does not match");
        String forged = signature.replace("timestamp:1", "timestamp:2");
        assertThatThrownBy(() -> Minisign.verify(pub, data, forged)).hasMessageContaining("trusted comment was changed");
        Minisign.Keys other = Minisign.readPublicKey(Minisign.publicKeyFile(Minisign.generate()));
        assertThatThrownBy(() -> Minisign.verify(other, data, signature)).hasMessageContaining("signed with another key");
    }

    @Test
    void secretKeysAreChecked() throws Exception {
        String file = Minisign.secretKeyFile(Minisign.generate());
        String line = file.lines().skip(1).findFirst().orElseThrow();
        byte[] raw = java.util.Base64.getDecoder().decode(line);
        raw[100] ^= 1;
        assertThatThrownBy(() -> Minisign.readSecretKey(java.util.Base64.getEncoder().encodeToString(raw))).hasMessageContaining("checksum");
        raw[100] ^= 1;
        raw[2] = 'S';
        raw[3] = 'c';
        assertThatThrownBy(() -> Minisign.readSecretKey(java.util.Base64.getEncoder().encodeToString(raw))).hasMessageContaining("encrypted");
    }
}
