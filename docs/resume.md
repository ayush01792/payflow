# Resume material

## Short project title

**PayFlow — Payment Orchestration Backend**

## One-line description

Production-oriented Java/Spring Boot backend exploring payment correctness under retries, asynchronous delivery and uncertain provider outcomes using mock HTTP providers.

## Three primary bullets

- Engineered merchant-authenticated payment/refund APIs with PostgreSQL-backed idempotency, Kafka lifecycle processing and a transactional outbox to preserve durable intent under retries and broker failures.
- Implemented recoverable provider orchestration using stable request keys, bounded retries, circuit breakers, fenced leases and webhook/status reconciliation; protected concurrent refund reservations with parent-payment row locks.
- Verified correctness with 271 passing Maven tests and real Testcontainers dependencies; completed a 15-minute local k6 soak without HTTP errors and a Kafka outage test in which all 180 accepted payments eventually succeeded.

## Five optional extended bullets

- Diagnosed concurrent API-key telemetry write contention with PostgreSQL query/lock snapshots; coalesced approximate writes from 1201 affected rows per 1201 primed reads to one per 1199 reads while preserving per-request authentication checks.
- Measured a local PostgreSQL CPU burst-limit change at ~40 HTTP/s mixed traffic: p95 53.05→42.49 ms and p99 130.35→112.66 ms in one paired test, with zero HTTP errors and documented cache/warmup/resource caveats.
- Implemented atomic Redis Lua merchant admission across two application replicas; verified a shared 10-request window yielded 10 accepts and 5 rate rejections from 15 concurrent requests.
- Added Prometheus/Grafana operational signals and OpenTelemetry correlation across HTTP/Kafka; used a 60-second steady JFR recording to investigate JVM behavior without speculative pool/GC tuning.
- Containerized the backend and provided Kubernetes probes, resource budgets, HPA/PDB configuration and two-replica operation; explicitly documented incomplete three-replica/HPA performance validation on the local Mac.

## Tech stack

Java 21 · Spring Boot 3.5 · Maven · PostgreSQL · Flyway · Redis · Kafka · Resilience4j · Spring Security · JUnit/Testcontainers · Prometheus/Grafana · OpenTelemetry · Docker Compose · Kubernetes/Kind · k6 · JFR

Use three bullets matched to the role, not all eight. The CPU comparison is local and short; do not turn it into a production throughput claim. Mock provider success is not settlement or proof of exactly-once real-world charging. [Measurement provenance](performance.md), [limitations](../README.md#known-limitations), [portfolio description](portfolio.md).
