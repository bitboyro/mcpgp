package ro.bitboy.mcpgp.runtime.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.prometheus.PrometheusHttpServer;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telemetry, off by default (audit-and-errors §4, ADR-0007): OpenTelemetry traces and metrics, pushed
 * over OTLP to the customer's collector, or scraped from a Prometheus endpoint (HTTP servers). One
 * span per tool call with a child per upstream request; {@code mcpgp.tool.calls},
 * {@code mcpgp.tool.duration} and {@code mcpgp.instance.up}. Only names, outcomes and codes go out:
 * never arguments, bodies or credentials. Set once per process (a config reload keeps it).
 */
public final class Telemetry implements AutoCloseable {

    static final AttributeKey<String> TOOL = AttributeKey.stringKey("mcpgp.tool");
    static final AttributeKey<String> API = AttributeKey.stringKey("mcpgp.api");
    static final AttributeKey<String> INSTANCE = AttributeKey.stringKey("mcpgp.instance");
    static final AttributeKey<String> CONTRACT = AttributeKey.stringKey("mcpgp.contract");
    static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("mcpgp.outcome");
    static final AttributeKey<String> CODE = AttributeKey.stringKey("mcpgp.code");

    private static final Logger LOG = LoggerFactory.getLogger(Telemetry.class);
    private static final Telemetry OFF = new Telemetry();
    private static volatile Telemetry current = OFF;

    private final SdkTracerProvider tracing;
    private final SdkMeterProvider metering;
    private final Tracer tracer;
    private final LongCounter calls;
    private final DoubleHistogram duration;
    /** Instance (api/id) → 1 when its last upstream request got an answer, 0 when it couldn't be reached. */
    private final Map<String, Long> up = new ConcurrentHashMap<>();

    private Telemetry() {
        this.tracing = null;
        this.metering = null;
        this.tracer = null;
        this.calls = null;
        this.duration = null;
    }

    private Telemetry(SdkTracerProvider tracing, SdkMeterProvider metering, String version) {
        this.tracing = tracing;
        this.metering = metering;
        this.tracer = tracing == null ? null : tracing.get("ro.bitboy.mcpgp.runtime", version);
        if (metering != null) {
            Meter meter = metering.get("ro.bitboy.mcpgp.runtime");
            this.calls = meter.counterBuilder("mcpgp.tool.calls").setDescription("Tool calls").setUnit("{call}").build();
            this.duration = meter.histogramBuilder("mcpgp.tool.duration").setDescription("Tool call duration").setUnit("s").build();
            meter.gaugeBuilder("mcpgp.instance.up").ofLongs().setDescription("1 when the instance's last upstream request got an answer")
                    .buildWithCallback(m -> up.forEach((key, value) -> {
                        int slash = key.indexOf('/');
                        m.record(value, Attributes.of(API, key.substring(0, slash), INSTANCE, key.substring(slash + 1)));
                    }));
        } else {
            this.calls = null;
            this.duration = null;
        }
    }

    /** The process's telemetry (off until {@link #start} is called). */
    public static Telemetry current() {
        return current;
    }

    /**
     * Starts telemetry from the {@code telemetry} config, unless it is off (the default, or
     * {@code OTEL_SDK_DISABLED=true}).
     *
     * @param host the address the Prometheus endpoint listens on (the server's host)
     */
    public static synchronized Telemetry start(JsonNode config, String serviceName, String version, String host, Map<String, String> env) {
        current.close();
        current = OFF;
        if (!config.path("enabled").asBoolean(false) || "true".equalsIgnoreCase(env.get("OTEL_SDK_DISABLED"))) {
            return current;
        }
        Set<String> signals = new HashSet<>();
        config.path("signals").forEach(s -> signals.add(s.asText()));
        if (signals.isEmpty()) {
            signals.addAll(Set.of("traces", "metrics"));
        }
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.builder()
                .put("service.name", config.path("serviceName").asText(serviceName))
                .put("service.version", version).build()));
        String exporter = config.path("exporter").asText("otlp");
        JsonNode otlp = config.path("otlp");
        String protocol = otlp.path("protocol").asText("grpc");
        String endpoint = otlp.path("endpoint").asText(protocol.equals("grpc") ? "http://localhost:4317" : "http://localhost:4318");
        Map<String, String> headers = new HashMap<>();
        otlp.path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asText()));

        SdkTracerProvider tracing = null;
        if (signals.contains("traces") && exporter.equals("otlp")) {
            SpanExporter spans = protocol.equals("grpc")
                    ? OtlpGrpcSpanExporter.builder().setEndpoint(endpoint).setHeaders(() -> headers).build()
                    : OtlpHttpSpanExporter.builder().setEndpoint(endpoint.replaceAll("/+$", "") + "/v1/traces").setHeaders(() -> headers).build();
            tracing = SdkTracerProvider.builder().setResource(resource).addSpanProcessor(BatchSpanProcessor.builder(spans).build()).build();
        }
        SdkMeterProvider metering = null;
        if (signals.contains("metrics")) {
            SdkMeterProviderBuilder meters = SdkMeterProvider.builder().setResource(resource);
            if (exporter.equals("prometheus")) {
                JsonNode prometheus = config.path("prometheus");
                meters.registerMetricReader(PrometheusHttpServer.builder().setHost(host).setPort(prometheus.path("port").asInt(9464)).build());
            } else {
                MetricExporter metrics = protocol.equals("grpc")
                        ? OtlpGrpcMetricExporter.builder().setEndpoint(endpoint).setHeaders(() -> headers).build()
                        : OtlpHttpMetricExporter.builder().setEndpoint(endpoint.replaceAll("/+$", "") + "/v1/metrics").setHeaders(() -> headers).build();
                meters.registerMetricReader(PeriodicMetricReader.builder(metrics).setInterval(Duration.ofSeconds(30)).build());
            }
            metering = meters.build();
        }
        current = new Telemetry(tracing, metering, version);
        LOG.info("Telemetry on: {} ({}), {}", exporter, exporter.equals("otlp") ? protocol + " to " + endpoint : "port "
                + config.at("/prometheus/port").asInt(9464), signals);
        return current;
    }

    /** A span for one tool call, current until it is closed (so upstream requests and inner calls become its children). */
    public Call call(String tool, String api) {
        if (tracer == null) {
            return new Call(null, null, this, tool, api);
        }
        Span span = tracer.spanBuilder("tool " + tool).setSpanKind(SpanKind.SERVER).setAttribute(TOOL, tool).startSpan();
        if (api != null) {
            span.setAttribute(API, api);
        }
        return new Call(span, span.makeCurrent(), this, tool, api);
    }

    /** A child span for one upstream request; {@link Upstream#end} with the status (null when there was no answer). */
    public Upstream upstream(String api, String instance, String method) {
        if (tracer == null) {
            return new Upstream(null, this, api, instance);
        }
        Span span = tracer.spanBuilder(method).setSpanKind(SpanKind.CLIENT).setAttribute(API, api).setAttribute(INSTANCE, instance)
                .setAttribute(AttributeKey.stringKey("http.request.method"), method).startSpan();
        return new Upstream(span, this, api, instance);
    }

    /** One tool call; closing it records the metrics and ends the span. */
    public static final class Call implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final Telemetry telemetry;
        private final String tool;
        private String api;
        private String instance;
        private String contract;
        private String outcome = "ok";
        private String code;
        private final long started = System.nanoTime();

        private Call(Span span, Scope scope, Telemetry telemetry, String tool, String api) {
            this.span = span;
            this.scope = scope;
            this.telemetry = telemetry;
            this.tool = tool;
            this.api = api;
        }

        public void result(String api, String instance, String contract, String outcome, String code) {
            if (api != null) {
                this.api = api;
            }
            this.instance = instance;
            this.contract = contract;
            this.outcome = outcome;
            this.code = code;
        }

        @Override
        public void close() {
            if (telemetry.calls != null) {
                AttributesBuilder attributes = Attributes.builder().put(TOOL, tool).put(OUTCOME, outcome);
                if (api != null) {
                    attributes.put(API, api);
                }
                if (instance != null) {
                    attributes.put(INSTANCE, instance);
                }
                if (code != null) {
                    attributes.put(CODE, code);
                }
                Attributes built = attributes.build();
                telemetry.calls.add(1, built);
                telemetry.duration.record((System.nanoTime() - started) / 1e9, built);
            }
            if (span != null) {
                if (instance != null) {
                    span.setAttribute(INSTANCE, instance);
                }
                if (contract != null) {
                    span.setAttribute(CONTRACT, contract);
                }
                span.setAttribute(OUTCOME, outcome);
                if (code != null) {
                    span.setAttribute(CODE, code);
                }
                if (outcome.equals("error")) {
                    span.setStatus(StatusCode.ERROR, code == null ? "error" : code);
                }
                scope.close();
                span.end();
            }
        }
    }

    /** One upstream request. */
    public static final class Upstream {
        private final Span span;
        private final Telemetry telemetry;
        private final String key;

        private Upstream(Span span, Telemetry telemetry, String api, String instance) {
            this.span = span;
            this.telemetry = telemetry;
            this.key = api + "/" + instance;
        }

        public void end(Integer status) {
            if (telemetry.metering != null) {
                telemetry.up.put(key, status == null ? 0L : 1L);
            }
            if (span != null) {
                if (status == null) {
                    span.setStatus(StatusCode.ERROR, "no answer");
                } else {
                    span.setAttribute(AttributeKey.longKey("http.response.status_code"), status.longValue());
                    if (status >= 500) {
                        span.setStatus(StatusCode.ERROR);
                    }
                }
                span.end();
            }
        }
    }

    /** Sends what is buffered and stops; telemetry is off afterwards. */
    @Override
    public void close() {
        synchronized (Telemetry.class) {
            if (current == this) {
                current = OFF;
            }
        }
        if (tracing != null) {
            tracing.close();
        }
        if (metering != null) {
            metering.close();
        }
    }
}
