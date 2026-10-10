package ro.bitboy.mcpgp.cli.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Ed25519 signatures in the minisign format (packaging.md §7.1), so packages can be verified with
 * {@code minisign -V} as well as {@code mcpgp verify}. Signatures are prehashed (BLAKE2b-512, algorithm
 * {@code ED}) and carry a signed trusted comment. Secret keys must be unencrypted (as from
 * {@code minisign -G -W} or {@code mcpgp keygen}); they come from a secret reference, never from files of
 * the definition.
 */
public final class Minisign {

    /** A key pair: the 8-byte key id and the raw 32-byte seed and public key. */
    public record Keys(byte[] keyId, byte[] seed, byte[] publicKey) {
    }

    // DER prefixes of Ed25519 keys in PKCS#8 / X.509 (RFC 8410), followed by the 32 raw bytes
    private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private Minisign() {
    }

    public static Keys generate() throws GeneralSecurityException {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] seed = Arrays.copyOfRange(pair.getPrivate().getEncoded(), PKCS8_PREFIX.length, PKCS8_PREFIX.length + 32);
        byte[] pk = Arrays.copyOfRange(pair.getPublic().getEncoded(), X509_PREFIX.length, X509_PREFIX.length + 32);
        byte[] keyId = new byte[8];
        new SecureRandom().nextBytes(keyId);
        return new Keys(keyId, seed, pk);
    }

    /** The secret key file (unencrypted). Keep it secret; give it to builds by reference only. */
    public static String secretKeyFile(Keys keys) {
        ByteBuffer b = ByteBuffer.allocate(2 + 2 + 2 + 32 + 8 + 8 + 8 + 64 + 32).order(ByteOrder.LITTLE_ENDIAN);
        b.put("Ed".getBytes(StandardCharsets.US_ASCII)).put(new byte[2]).put("B2".getBytes(StandardCharsets.US_ASCII));
        b.put(new byte[32]).putLong(0).putLong(0);           // no key derivation: unencrypted
        b.put(keys.keyId()).put(keys.seed()).put(keys.publicKey());
        b.put(checksum(keys));
        return "untrusted comment: minisign unencrypted secret key\n" + Base64.getEncoder().encodeToString(b.array()) + "\n";
    }

    public static String publicKeyFile(Keys keys) {
        return "untrusted comment: minisign public key " + keyIdText(keys.keyId()) + "\n" + publicKeyLine(keys) + "\n";
    }

    public static String publicKeyLine(Keys keys) {
        ByteBuffer b = ByteBuffer.allocate(42);
        b.put("Ed".getBytes(StandardCharsets.US_ASCII)).put(keys.keyId()).put(keys.publicKey());
        return Base64.getEncoder().encodeToString(b.array());
    }

    /** Reads a secret key: the file's text, or just its base64 line. */
    public static Keys readSecretKey(String text) {
        byte[] raw = decodeLine(text, "secret key");
        if (raw.length != 158 || raw[0] != 'E' || raw[1] != 'd') {
            throw new IllegalArgumentException("not a minisign secret key");
        }
        if (raw[2] != 0 || raw[3] != 0) {
            throw new IllegalArgumentException("the signing key is encrypted; give the build an unencrypted key "
                    + "(minisign -G -W, or mcpgp keygen) through a secret reference");
        }
        byte[] keyId = Arrays.copyOfRange(raw, 54, 62);
        byte[] seed = Arrays.copyOfRange(raw, 62, 94);
        byte[] pk = Arrays.copyOfRange(raw, 94, 126);
        Keys keys = new Keys(keyId, seed, pk);
        if (!Arrays.equals(checksum(keys), Arrays.copyOfRange(raw, 126, 158))) {
            throw new IllegalArgumentException("the signing key is damaged (checksum mismatch)");
        }
        return keys;
    }

    /** Reads a public key: the file's text, or just its base64 line. Returns {keyId(8), publicKey(32)}. */
    public static Keys readPublicKey(String text) {
        byte[] raw = decodeLine(text, "public key");
        if (raw.length != 42 || raw[0] != 'E' || raw[1] != 'd') {
            throw new IllegalArgumentException("not a minisign public key");
        }
        return new Keys(Arrays.copyOfRange(raw, 2, 10), null, Arrays.copyOfRange(raw, 10, 42));
    }

    /** A {@code .sig} file for {@code data}, with this trusted comment. */
    public static String sign(Keys keys, byte[] data, String trustedComment) throws GeneralSecurityException {
        byte[] signature = ed25519(keys.seed(), Blake2b.hash(data, 64));
        ByteBuffer sig = ByteBuffer.allocate(74);
        sig.put("ED".getBytes(StandardCharsets.US_ASCII)).put(keys.keyId()).put(signature);
        byte[] global = ed25519(keys.seed(), concat(signature, trustedComment.getBytes(StandardCharsets.UTF_8)));
        return "untrusted comment: signature from mcpgp\n" + Base64.getEncoder().encodeToString(sig.array()) + "\n"
                + "trusted comment: " + trustedComment + "\n" + Base64.getEncoder().encodeToString(global) + "\n";
    }

    /** Checks a {@code .sig} file; returns the trusted comment, or throws with the reason. */
    public static String verify(Keys publicKey, byte[] data, String signatureFile) throws GeneralSecurityException {
        String[] lines = signatureFile.strip().split("\n");
        if (lines.length < 4 || !lines[2].startsWith("trusted comment: ")) {
            throw new IllegalArgumentException("not a minisign signature file");
        }
        byte[] sig = Base64.getDecoder().decode(lines[1].strip());
        if (sig.length != 74) {
            throw new IllegalArgumentException("not a minisign signature");
        }
        String algorithm = new String(sig, 0, 2, StandardCharsets.US_ASCII);
        if (!Arrays.equals(Arrays.copyOfRange(sig, 2, 10), publicKey.keyId())) {
            throw new IllegalArgumentException("signed with another key (" + keyIdText(Arrays.copyOfRange(sig, 2, 10)) + ")");
        }
        byte[] signature = Arrays.copyOfRange(sig, 10, 74);
        byte[] message = switch (algorithm) {
            case "ED" -> Blake2b.hash(data, 64);
            case "Ed" -> data;
            default -> throw new IllegalArgumentException("unknown signature algorithm " + algorithm);
        };
        PublicKey pk = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, publicKey.publicKey())));
        if (!check(pk, message, signature)) {
            throw new IllegalArgumentException("the signature does not match the file");
        }
        String trusted = lines[2].substring("trusted comment: ".length());
        if (!check(pk, concat(signature, trusted.getBytes(StandardCharsets.UTF_8)), Base64.getDecoder().decode(lines[3].strip()))) {
            throw new IllegalArgumentException("the trusted comment was changed");
        }
        return trusted;
    }

    public static String keyIdText(byte[] keyId) {
        // minisign shows the id as a little-endian 64-bit number in hex
        byte[] reversed = new byte[8];
        for (int i = 0; i < 8; i++) {
            reversed[i] = keyId[7 - i];
        }
        return HexFormat.of().withUpperCase().formatHex(reversed);
    }

    private static byte[] checksum(Keys keys) {
        return Blake2b.hash(concat("Ed".getBytes(StandardCharsets.US_ASCII), keys.keyId(), keys.seed(), keys.publicKey()), 32);
    }

    private static byte[] ed25519(byte[] seed, byte[] message) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(concat(PKCS8_PREFIX, seed)));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    private static boolean check(PublicKey key, byte[] message, byte[] signature) throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signature);
    }

    private static byte[] decodeLine(String text, String what) {
        for (String line : text.strip().split("\n")) {
            String l = line.strip();
            if (!l.isEmpty() && !l.toLowerCase(Locale.ROOT).startsWith("untrusted comment:")) {
                try {
                    return Base64.getDecoder().decode(l);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("the " + what + " is not valid base64");
                }
            }
        }
        throw new IllegalArgumentException("no " + what + " found");
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
