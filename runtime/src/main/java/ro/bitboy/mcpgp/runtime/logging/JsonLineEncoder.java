package ro.bitboy.mcpgp.runtime.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Operational log lines (runtime-config §4.3): one JSON object per line with {@code ts}, {@code level},
 * {@code logger}, {@code message}, {@code cid} (correlation id, from the MDC) and {@code error}.
 */
public final class JsonLineEncoder extends EncoderBase<ILoggingEvent> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] EMPTY = new byte[0];

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("level", switch (event.getLevel().toString()) {
            case "WARN" -> "warn";
            case "TRACE" -> "debug";
            default -> event.getLevel().toString().toLowerCase(Locale.ROOT);
        });
        line.put("logger", event.getLoggerName());
        line.put("message", event.getFormattedMessage());
        String cid = event.getMDCPropertyMap().get("cid");
        if (cid != null) {
            line.put("cid", cid);
        }
        if (event.getThrowableProxy() != null) {
            line.put("error", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        try {
            return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            return ("{\"level\":\"error\",\"message\":\"log line could not be encoded\"}\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }
}
