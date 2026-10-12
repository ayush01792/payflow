# PayFlow

## Overview

PayFlow is a **production-oriented payment orchestration platform** built with Java and Spring Boot. It provides merchant-authenticated payment and refund APIs while exploring idempotency, asynchronous lifecycle processing, provider routing, retries, circuit breaking, reconciliation and distributed concurrency. Providers are simulated HTTP systems; this is not a live payment gateway.

Start with [architecture](docs/architecture.md), [API examples](docs/api.md), or [Docker quickstart](docs/local-development.md). For interviews: [project guide](docs/interview-guide.md), [resume bullets](docs/resume.md), [portfolio description](docs/portfolio.md).

## Merchant Dashboard

The responsive [merchant dashboard](frontend/README.md) adds React, TypeScript, Material UI, React Router, Axios and TanStack Query under `frontend/`. It presents existing payment/refund APIs, session-local refund submissions, a current-page status chart and public health. It does not redesign the backend or invent aggregate/refund-list APIs.

Fill the blank secrets in `.env` first, then:

```sh
docker compose up --build -d
```

Dashboard: http://localhost:3001 · backend: http://localhost:8080 · Grafana: http://localhost:3000. Bootstrap a local merchant with the existing helper, then manually enter the key at `/connect`. Browser credentials are a local portfolio demo only; real web applications should keep merchant API keys server-side. [Frontend setup/security/testing](frontend/README.md), [manual screenshot slots](docs/screenshots/README.md).

## Why I Built It

I built PayFlow to practice backend engineering beyond CRUD: how to preserve intent when Kafka is unavailable, avoid repeating a charge after response loss, and coordinate refund balance across replicas. The project prioritizes explicit failure semantics, meaningful integration tests and measured trade-offs over unsupported scale claims.

## Architecture

One logical Spring Boot application runs API handlers, outbox workers, Kafka consumers and reconciliation schedulers. PostgreSQL owns durable state and transactions. Redis serves cache/limiter coordination; Kafka carries lifecycle events; provider adapters call mock HTTP endpoints. Replicas share claims and durable identities.

[Architecture diagrams and component map](docs/architecture.md) include payment/refund paths, consistency boundaries, leases/fencing and partition limits.

## Key Features

- Payment/refund APIs with merchant isolation and durable request idempotency
- Transactional outbox, at-least-once Kafka delivery and durable consumer deduplication
- Stable provider keys, restricted failover, retry/circuit breaker and ambiguous-outcome reconciliation
- Concurrent refund reservation and internal Saga-style compensation
- Redis cache fallback and atomic shared merchant rate admission
- Operational metrics, tracing, Docker/Kubernetes deployment and reproducible correctness/load tests

## Tech Stack

| Area | Tools |
|---|---|
| Application | Java 21, Spring Boot 3.5.16, Maven, Web, Validation, JPA/JDBC |
| State and coordination | PostgreSQL 17.6, Flyway, Redis 7.4.2 |
| Messaging/resilience | Kafka 3.9.1, Spring Kafka, Resilience4j |
| Security/telemetry | Spring Security, Micrometer, Prometheus, Grafana, OpenTelemetry |
| Deployment/testing | Docker Compose, Kubernetes/Kind, JUnit 5, Testcontainers, k6, JFR |

## Payment Flow

1. Authenticate the merchant, validate the request and check idempotency before rate admission.
2. Atomically commit payment CREATED, request fingerprint and creation outbox intent; return 201 (or 200 replay).
3. Publish via outbox; Kafka consumer transitions to PROCESSING and emits provider-work intent.
4. Claim provider work, invoke with a stable key outside DB locks, and persist a fenced result.
5. Complete SUCCESS/FAILED or preserve PENDING_CONFIRMATION for signed webhook/status reconciliation; terminal changes also create outbox intent.

HTTP acceptance is separate from business completion. [Normal sequence](docs/architecture.md#successful-payment-sequence), [timeout sequence](docs/failure-handling.md#why-timeout-is-not-failure).

## Reliability & Distributed Systems Design

Local PostgreSQL transactions protect payment/idempotency/outbox, consumer transitions and refund reservations. Kafka/provider/cache boundaries are eventual. Outbox publication can repeat after acknowledgement loss; consumers must deduplicate. Leases recover crashed work, fencing rejects late owners, and provider keys protect external retry identity. A timeout is not proof of failure; unknown charge outcomes never trigger blind failover.

[Four idempotency layers](docs/idempotency.md) · [outbox](docs/outbox.md) · [failure matrix](docs/failure-handling.md) · [consistency and concurrency](docs/architecture.md#consistency-model).

## Security

Merchant API keys use lookup prefixes and server-peppered HMAC digests, fresh status/expiry checks and owned-resource predicates. Webhooks require raw-body HMAC verification and durable inbox checks. Containers run non-root; local credentials stay in ignored files/Secret references. No real card credentials are handled. [Security model and deployment responsibilities](docs/security.md).

## Observability

Actuator probes, bounded custom metrics, structured correlation logs and HTTP/Kafka trace propagation support diagnosis. Grafana covers request/provider/refund/outbox/cache/limiter/JVM/pool signals; Prometheus alerts are in source. Local OTEL exports to a debug collector, not a hosted persistent trace platform. [Operations](docs/local-development.md#monitoring-and-failure-recovery), [monitoring assets](observability/).

## Performance Results

Measured on an 8 GB Apple Silicon Mac, ~3.82 GiB Docker VM and two 1-CPU/1-GiB application Pods sharing dependencies and mock providers. These are local results, not production capacity.

| Evidence | Observed result |
|---|---|
| Primed reads, ~20 HTTP/s, 60 s | p95 36.04→14.15 ms after audit-write coalescing; zero errors/drops |
| Local PG CPU burst limit, ~40 HTTP/s mixed, 60 s | p95 53.05→42.49 ms; p99 130.35→112.66 ms; zero errors, drops 5→2 |
| Fifteen-minute soak, 2 HTTP/s | p50/p95/p99 32.92/169.85/418.36 ms; zero errors/drops/OOM |
| Kafka outage/recovery | All 180 accepted payments eventually SUCCESS; API p95 1261.29 ms missed the target |

Single-run warmup/cache/dataset differences limit causal claims. Mixed/stress/spike failures are preserved; no general high-concurrency throughput gain is established. **three-replica/HPA validation could not be completed on the local Mac environment**.

[Concise performance summary](docs/performance.md) · [actual baseline](performance/BASELINE.md) · [optimizations and rejected candidates](performance/OPTIMIZATIONS.md) · [reproduction](performance/README.md).

## Local Development

Preferred: Docker Compose. Preserve any existing private environment file.

```sh
cp .env.example .env
chmod 600 .env
# Fill all blank secrets with independent random values (openssl rand -hex 32).
docker compose config --quiet
docker compose up --build -d
docker compose ps
curl --fail http://localhost:8080/api/v1/health
./scripts/docker-bootstrap-merchant.sh local-demo-merchant secrets/local-merchant.api-key
```

The helper saves the one-time key securely and refuses overwrite. Use it in [authenticated payment/refund curl examples](docs/api.md); do not send merchant IDs to select tenants. Requirements: Docker with Compose v2; Java 21/Maven for host-side builds/tests. [Full local guide](docs/local-development.md).

## Docker

The multi-stage application Dockerfile builds a JAR and runs a non-root Java 21 JRE. Compose starts the app, PostgreSQL, Redis, Kafka and monitoring with dependency health checks and loopback host ports. Image builds skip tests; verify separately. `docker compose stop` preserves volumes; volume deletion is destructive. [.env template](.env.example) · [Compose](compose.yml) · [Dockerfile](Dockerfile).

## Kubernetes

Kind/Kustomize manifests provide probes, graceful termination, resource requests/limits, HPA and PDB. Base HPA allows 2–6; local overlay caps at two. Local dependencies are single-instance development services, not HA infrastructure. Configuration exists, but full three-replica autoscaling performance validation was limited by local resources.

```sh
docker build -t payflow:local .
# Fresh cluster only; follow the detailed guide for secrets and Collector image.
kind create cluster --name payflow --config k8s/local/kind-config.yaml
kind load docker-image payflow:local --name payflow
# Prepare k8s/local/secrets.env and load the Collector image before applying.
kubectl apply -k k8s/local
```

Use the [complete Kubernetes quickstart](docs/local-development.md#kubernetes-quickstart) to prepare secrets, load both images, install Metrics Server, wait for readiness and bootstrap a merchant. [Manifests](k8s/).

## Testing

```sh
mvn clean verify
```

**271 tests passed, zero failures/errors/skips**, in both the final performance-step run and the documentation-step rerun. Testcontainers uses actual PostgreSQL, Redis and Kafka; tests cover HTTP, webhooks, timeout recovery, concurrency, refunds, security and telemetry. Load/JFR tests are separate and were not repeated for documentation edits. [Test layers and local settings](docs/testing.md).

## Project Structure

```text
src/main/java/com/payflow/payment/  API, services, stores, providers, security, telemetry
src/main/resources/                configuration, Flyway migrations, Redis Lua
src/test/                          correctness and integration tests
scripts/                           secure merchant bootstrap and deployment smoke helpers
performance/                       k6, profiling helpers and curated measurement reports
observability/                     Prometheus, Grafana and OTEL configuration
k8s/                               base and local Kubernetes manifests
frontend/                          responsive merchant dashboard, unit/browser tests, static container
docs/                              architecture, trade-offs, API and portfolio/interview guides
```

[Preserved development history](docs/development-history.md) retains earlier detailed notes and per-step validation; it is historical context, not the primary onboarding path.

## Design Trade-offs

Durable PostgreSQL idempotency adds DB work; outbox polling adds delay/operations; Kafka adds replay and broker complexity; Redis reads are eventually consistent; fail-closed admission trades availability for shared coordination. Unknown outcomes require reconciliation instead of optimistic failover. [Decision table with advantages, costs and alternatives](docs/design-decisions.md).

## Known Limitations

- Mock providers, not real payment networks; no settlement, chargebacks/disputes or real PCI card-data handling.
- One logical application, not independently deployed microservices.
- Local PostgreSQL/Redis/Kafka deployment is not production HA; no cloud deployment/IaC.
- Three-replica/HPA performance validation is incomplete on the local Mac; stress/spike targets also failed.
- No full IAM/OAuth/SSO, external secret manager, admin UI or reconciliation/DLT replay console.
- TLS/cloud perimeter, backups and recovery policy remain deployment responsibilities; cache views can be stale and unresolved work needs operational attention.
- No automatic large-scale outbox/dedup retention strategy or proven production throughput.

## Future Improvements

Potential work: real sandbox provider contracts, settlement/dispute models, managed HA dependencies and secret storage, tested backups, replay/admin tooling, CDC outbox, cursor pagination and measured lag-based autoscaling. Multi-region/sharding would need explicit ownership/consistency design. [Roadmap and alternatives](docs/design-decisions.md#realistic-future-roadmap-not-implemented).

The backend remains complete through Step 16. The frontend adds presentation only; payment behavior and merchant authentication remain unchanged.
