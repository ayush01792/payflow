# The official Collector is distroless. Add only a static HTTP probe executable.
FROM busybox:1.37.0-musl AS probe
FROM otel/opentelemetry-collector:0.162.0
COPY --from=probe /bin/busybox /busybox
USER 10001:10001
