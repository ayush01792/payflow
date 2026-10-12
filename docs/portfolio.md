# Portfolio and LinkedIn material

## Description

PayFlow is a production-oriented payment orchestration backend built with Java 21 and Spring Boot. I focused on preserving payment intent and avoiding duplicate business actions under retries, concurrent replicas and distributed failures. PostgreSQL-backed idempotency and a transactional outbox feed asynchronous Kafka lifecycle processing; Redis provides cache-aside reads and shared merchant rate limiting. Mock HTTP providers exercise bounded retries, circuit breakers, restricted failover, uncertain outcomes, signed webhooks and active reconciliation. Refunds reserve balance transactionally and stay with the original provider, with an internal compensation path. The project includes API-key authentication, tenant isolation, Docker/Kubernetes configuration and Prometheus/OpenTelemetry observability. Validation includes 271 passing correctness tests, k6 workloads, a fifteen-minute local soak and JFR profiling. Performance claims remain bounded by the measured laptop environment; three-replica/HPA validation is explicitly incomplete.

## Compact feature list

- Durable idempotency and transactional outbox
- Kafka lifecycle events and consumer deduplication
- Redis cache and distributed merchant admission
- Resilient mock-provider orchestration and reconciliation
- Concurrent refunds and Saga-style compensation
- API-key authentication and tenant isolation
- Metrics, structured logging and tracing
- Docker/Compose and Kubernetes configuration
- Testcontainers, k6 and evidence-based profiling

This copy is ready to adapt for a pinned repository, LinkedIn or a portfolio; nothing has been published externally.
