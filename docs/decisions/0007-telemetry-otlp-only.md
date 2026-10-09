# 0007. Telemetry via OTLP only; destinations outside the server

- Status: Accepted
- Date: 2026-10-09

## Decision

The server exports telemetry only via OpenTelemetry (OTLP push; Prometheus scrape for HTTP builds),
off by default, never including arguments or bodies. Destinations such as S3, Grafana or Datadog are
configured in an OpenTelemetry Collector. Audit uses its own file; extra audit sinks (S3-compatible)
are P2, with file + shipper documented first.

## Consequences

- No cloud SDKs or credentials in every server.
- Builds ship an example Collector config.
