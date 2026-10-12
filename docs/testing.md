# Testing and verification

Step 15 finished with **271 Maven tests, zero failures/errors/skips**. Step 16 reran `mvn clean verify` on 11 October 2026 and again passed **271, zero failures/errors/skips**, including executable JAR packaging. Documentation does not add runtime features or new Java tests.

## Layers in this repository

| Layer | Concrete evidence / purpose |
|---|---|
| Focused unit tests | Event publisher transport arguments, metric/correlation behavior and webhook signature verification without a full provider workflow. |
| MVC slice | `HealthControllerTest` uses `@WebMvcTest`, MockMvc and the route/security boundary. |
| Repository slice | `PaymentRepositoryIntegrationTest` uses `@DataJpaTest`, real PostgreSQL and `Replace.NONE`; validates schema/query/pagination behavior. |
| Context | `PaymentApplicationTests` verifies Spring wiring with real configured container dependencies. |
| HTTP integration | Payment/idempotency/Redis/security suites exercise validation, replay/conflict, tenant ownership, cache fallback and admission policy. |
| Kafka/outbox | Real broker, record key contracts, duplicate delivery, rollback, retry/DLT, publication recovery and scheduling. |
| Provider HTTP | Real HTTP/socket boundary against controlled mocks; success/decline/retry, timeout ambiguity, restricted fallback, circuit state and stable external key. |
| Reconciliation/webhooks | Raw-body HMAC, duplicate/conflicting callbacks, provider-reference checks, lost callback/status polling and leased recovery. |
| Refund/concurrency | Parallel reservations, over-refund protection, refund replay, original provider, uncertain reservation and compensation. |
| Observability | Probe exposure/dependency policy, bounded metrics, correlations, Kafka trace propagation and telemetry-export failure resilience. |
| Deployment smoke | Existing Docker/Kubernetes bootstrap/smoke helpers verify configured deployment and owned API flow. |
| Performance | Separate k6 runs and JFR/resource/SQL/correctness evidence; not executed by Maven. |

Tests live under [src/test](../src/test/java/com/payflow/payment/). JUnit 5, Spring Boot Test, MockMvc, Mockito and Awaitility are used according to the boundary. Awaitility waits for asynchronous outcomes with bounded deadlines rather than assuming instantaneous consumer completion.

## Why real containers

PostgreSQL uniqueness, isolation, row locks, `SKIP LOCKED`, JSONB, database timestamps and Flyway SQL are central to correctness. H2 cannot establish those PostgreSQL-specific semantics. Redis Lua/expiry must be exercised on Redis; mocking an increment cannot prove atomic cross-client admission. Kafka partitions, offsets, deserialization and rebalances need a broker. Testcontainers provides repeatable disposable dependencies; pure mocks remain useful for isolated adapter/argument checks but cannot establish distributed guarantees.

Mock HTTP providers deliberately model external effects and lost responses, not just an in-process successful method. They remain simulations: these tests do not certify any real payment network or settlement integration.

## Run

Java 21, Maven and a reachable Docker daemon:

```sh
mvn clean verify
```

On this small Mac, the successful verification used a 1 GiB forked test JVM and 512 MiB Maven heap. Local app/broker/monitoring were temporarily paused while Testcontainers ran, with their saved counts/HPA restored afterward. If using Docker Desktop's user socket:

```sh
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
MAVEN_OPTS=-Xmx512m mvn -B -ntp -Dapi.version=1.44 -DargLine=-Xmx1024m clean verify
```

Socket/API override is the verified local compatibility setting, not a requirement for every Docker installation. Tests supply isolated credentials and do not require real provider keys. Do not run load generation concurrently on constrained hardware. Initial Step 15 deadline-sensitive failures and the corrected async observability assertion are retained in [baseline diagnostics](../performance/BASELINE.md); assertions/timeouts were not relaxed to force a pass.

## Step 16 validation

Full Maven verification passed. `docker compose config --quiet` passed without exposing resolved credentials. Strict Kubernetes 1.36 schema validation accepted 8 base, 28 local and 9 performance resources. All 84 relative file links resolve; all 13 Mermaid blocks (including archived history) parse successfully. The 48 interview questions have model answers; the portfolio description is 125 words. Both normal app replicas and supporting services were restored Ready, with the saved local two-replica HPA cap. Migration V1–V8 hashes remain unchanged. No long performance rerun is needed for Markdown/ignore changes. Historical per-step counts remain in [development history](development-history.md), not substituted for the current test result.
