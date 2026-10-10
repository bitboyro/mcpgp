package ro.bitboy.mcpgp.packager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reproducible zip and jar writing: fixed timestamps (the build time), no extra fields, entries in a
 * stable order. Copying keeps each entry's compression method, which Spring Boot needs for its nested
 * jars (stored, uncompressed).
 */
final class Zips {

    private Zips() {
    }

    /** The server jar: every entry of the runtime jar, in order, then {@code extra} (path → bytes). */
    static void copyWith(Path source, Map<String, byte[]> extra, Instant time, Path target) throws IOException {
        try (ZipFile in = new ZipFile(source.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (extra.containsKey(entry.getName())) {
                    continue;
                }
                try (InputStream data = in.getInputStream(entry)) {
                    write(out, entry.getName(), data.readAllBytes(), entry.getMethod(), time);
                }
            }
            for (Map.Entry<String, byte[]> add : extra.entrySet()) {
                write(out, add.getKey(), add.getValue(), ZipEntry.DEFLATED, time);
            }
        }
    }

    /** A zip of these files (path inside the zip → file), sorted by path. */
    static void zip(Map<String, Path> files, Instant time, Path target) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Map.Entry<String, Path> file : new java.util.TreeMap<>(files).entrySet()) {
                byte[] bytes = Files.readAllBytes(file.getValue());
                // jars inside are already compressed
                write(out, file.getKey(), bytes, file.getKey().endsWith(".jar") ? ZipEntry.STORED : ZipEntry.DEFLATED, time);
            }
        }
    }

    private static void write(ZipOutputStream out, String name, byte[] bytes, int method, Instant time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        // the UTC time as is: no timezone conversion, no extra timestamp fields, same bytes everywhere
        entry.setTimeLocal(java.time.LocalDateTime.ofInstant(time, java.time.ZoneOffset.UTC));
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }
}
