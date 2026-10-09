package ro.bitboy.mcpgp.runtime.config;

import java.util.List;

/** The runtime config is invalid; startup stops and every problem is reported (runtime-config §7). */
public final class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super("Invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
