package ro.bitboy.mcpgp.platform.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The platform's own build, as the build recorded it in META-INF/build-info.properties: the release version, the
 * time the artifact was built (never the time it started) and the commit when the build was given one.
 */
public record BuildInfo(String version, String time, String commit) {

    private static final BuildInfo CURRENT = load();

    public static BuildInfo current() {
        return CURRENT;
    }

    static BuildInfo load() {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // not recorded: shown as such
        }
        String commit = blank(properties.getProperty("build.commit"));
        return new BuildInfo(blank(properties.getProperty("build.version")), blank(properties.getProperty("build.time")),
                "unknown".equals(commit) ? null : commit);
    }

    /** A build given no commit records "unknown" (the build plugin refuses an empty value). */
    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** For the settings endpoint: what's recorded, plus the Java runtime it runs on now. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", version);
        map.put("time", time);
        map.put("commit", commit);
        map.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
        return map;
    }
}
