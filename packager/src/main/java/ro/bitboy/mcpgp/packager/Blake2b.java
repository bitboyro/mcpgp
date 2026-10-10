package ro.bitboy.mcpgp.packager;

/**
 * BLAKE2b (RFC 7693), unkeyed, for minisign signatures (prehash) and key checksums. The JDK has no
 * BLAKE2; this is the reference algorithm, checked against the RFC's test values.
 */
final class Blake2b {

    private static final long[] IV = {
            0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
            0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L};

    private static final int[][] SIGMA = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
            {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
            {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
            {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
            {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
            {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
            {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
            {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
            {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
            {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0}};

    private Blake2b() {
    }

    /** The BLAKE2b digest of {@code data}, {@code length} bytes long (1–64). */
    static byte[] hash(byte[] data, int length) {
        long[] h = IV.clone();
        h[0] ^= 0x01010000L ^ length;
        long counter = 0;
        int offset = 0;
        byte[] block = new byte[128];
        // all full blocks except the last one
        while (data.length - offset > 128) {
            System.arraycopy(data, offset, block, 0, 128);
            counter += 128;
            compress(h, block, counter, false);
            offset += 128;
        }
        java.util.Arrays.fill(block, (byte) 0);
        int rest = data.length - offset;
        System.arraycopy(data, offset, block, 0, rest);
        counter += rest;
        compress(h, block, counter, true);
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (h[i / 8] >>> (8 * (i % 8)));
        }
        return out;
    }

    private static void compress(long[] h, byte[] block, long counter, boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) {
            long word = 0;
            for (int b = 7; b >= 0; b--) {
                word = (word << 8) | (block[i * 8 + b] & 0xffL);
            }
            m[i] = word;
        }
        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;
        if (last) {
            v[14] = ~v[14];
        }
        for (int round = 0; round < 12; round++) {
            int[] s = SIGMA[round % 10];
            mix(v, 0, 4, 8, 12, m[s[0]], m[s[1]]);
            mix(v, 1, 5, 9, 13, m[s[2]], m[s[3]]);
            mix(v, 2, 6, 10, 14, m[s[4]], m[s[5]]);
            mix(v, 3, 7, 11, 15, m[s[6]], m[s[7]]);
            mix(v, 0, 5, 10, 15, m[s[8]], m[s[9]]);
            mix(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            mix(v, 2, 7, 8, 13, m[s[12]], m[s[13]]);
            mix(v, 3, 4, 9, 14, m[s[14]], m[s[15]]);
        }
        for (int i = 0; i < 8; i++) {
            h[i] ^= v[i] ^ v[i + 8];
        }
    }

    private static void mix(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }
}
