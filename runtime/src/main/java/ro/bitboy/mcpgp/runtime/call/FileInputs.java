package ro.bitboy.mcpgp.runtime.call;

import com.fasterxml.jackson.databind.JsonNode;
import ro.bitboy.mcpgp.runtime.config.RuntimeConfig;
import ro.bitboy.mcpgp.runtime.mcp.ToolError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * File inputs (tools-contract §9, runtime-config §6): the file object {@code {base64, filename,
 * contentType}} or {@code {path}}, in the forms the operator enabled, within size caps; paths stay
 * inside the configured root (also through symlinks).
 */
final class FileInputs {

    /** A file ready to send. */
    record FileData(byte[] bytes, String filename, String contentType) {
    }

    private final RuntimeConfig.Files config;

    FileInputs(RuntimeConfig.Files config) {
        this.config = config;
    }

    /**
     * @param allowed the content types allowed for this input (from the manifest); the first is the default
     * @param field   where the file is in the arguments, for messages ({@code body.document})
     */
    FileData read(JsonNode file, List<String> allowed, String field) {
        if (file == null || !file.isObject()) {
            throw invalid("FILE_INVALID", field + " must be a file object: {base64, filename, contentType} or {path}");
        }
        FileData data = file.has("path") ? fromPath(file, field) : fromBase64(file, field);
        String type = data.contentType();
        if (type == null) {
            type = allowed.isEmpty() || allowed.get(0).contains("*") ? "application/octet-stream" : allowed.get(0);
        } else if (!isAllowed(type, allowed)) {
            throw invalid("FILE_CONTENT_TYPE", field + " has content type " + type + "; allowed: " + String.join(", ", allowed));
        }
        return new FileData(data.bytes(), data.filename(), type);
    }

    private FileData fromBase64(JsonNode file, String field) {
        if (!config.base64Enabled()) {
            throw invalid("FILE_FORM_DISABLED", field + ": inline base64 files are disabled on this server");
        }
        String text = file.path("base64").asText("");
        long maxBytes = config.base64MaxKb() * 1024L;
        if (text.length() / 4L * 3L > maxBytes + 3) {
            throw invalid("FILE_TOO_LARGE", field + " is larger than the limit of " + config.base64MaxKb() + " KB");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw invalid("FILE_INVALID_BASE64", field + ".base64 is not valid base64");
        }
        if (bytes.length > maxBytes) {
            throw invalid("FILE_TOO_LARGE", field + " is " + kb(bytes.length) + " KB; the limit is " + config.base64MaxKb() + " KB");
        }
        return new FileData(bytes, file.path("filename").asText(null), file.path("contentType").asText(null));
    }

    private FileData fromPath(JsonNode file, String field) {
        if (!config.pathEnabled()) {
            throw invalid("FILE_FORM_DISABLED", field + ": files by path are disabled on this server; send {base64, filename, contentType}");
        }
        Path root = config.pathRoot().toAbsolutePath().normalize();
        Path target = root.resolve(file.path("path").asText("")).normalize();
        if (!target.startsWith(root)) {
            throw invalid("FILE_OUTSIDE_ROOT", field + ": the path leaves the file root");
        }
        if (!Files.exists(target)) {
            throw invalid("FILE_NOT_FOUND", field + ": no such file under the file root");
        }
        try {
            Path real = target.toRealPath();      // follows symlinks: the target must still be inside
            if (!real.startsWith(root.toRealPath())) {
                throw invalid("FILE_OUTSIDE_ROOT", field + ": the path leaves the file root");
            }
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw invalid("FILE_NOT_FOUND", field + ": not a readable file");
            }
            long size = Files.size(real);
            if (size > config.pathMaxKb() * 1024L) {
                throw invalid("FILE_TOO_LARGE", field + " is " + kb(size) + " KB; the limit is " + config.pathMaxKb() + " KB");
            }
            return new FileData(Files.readAllBytes(real), real.getFileName().toString(), Files.probeContentType(real));
        } catch (IOException e) {
            throw invalid("FILE_NOT_FOUND", field + ": the file cannot be read");
        }
    }

    static boolean isAllowed(String type, List<String> allowed) {
        String base = type.split(";")[0].strip().toLowerCase();
        for (String a : allowed) {
            String candidate = a.split(";")[0].strip().toLowerCase();
            if (candidate.equals(base) || candidate.equals("*/*")
                    || candidate.endsWith("/*") && base.startsWith(candidate.substring(0, candidate.length() - 1))) {
                return true;
            }
        }
        return allowed.isEmpty();
    }

    private static long kb(long bytes) {
        return (bytes + 1023) / 1024;
    }

    private static ToolError invalid(String reason, String message) {
        return new ToolError("INPUT_VALIDATION", reason + ": " + message);
    }
}
