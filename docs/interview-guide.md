# PayFlow interview guide

These are project-specific answers. Describe current behavior from source and label future ideas as future design. Do not claim real payment processing, exactly-once effects, production HA or a proven HPA scale-up.

## Thirty-second explanation

“I built PayFlow, a Java 21/Spring Boot payment orchestration backend focused on correctness under retries and distributed failures. PostgreSQL owns idempotency and workflow state, a transactional outbox feeds Kafka lifecycle processing, and Redis handles caching and shared merchant admission. Mock HTTP providers let me test retries, restricted failover, uncertain timeouts, webhooks, reconciliation and refunds. I containerized it, ran two Kubernetes replicas, and measured it with k6, JFR and Prometheus. The complete correctness suite passes 271 tests; three-replica/HPA validation remains limited by my local Mac.”

## Two-minute explanation

“I wanted to explore what happens when a payment request is retried, a process crashes, or a provider charges but its response disappears. PayFlow accepts a merchant-authenticated intent, committing the payment, idempotency record and outbox event together in PostgreSQL. Kafka then drives CREATED to PROCESSING and provider orchestration asynchronously. The application is one logical backend replicated across Pods, not a microservice fleet.

The most interesting challenge was uncertainty across a database and an external HTTP side effect. A timeout cannot safely mean FAILED: the provider may already have charged. I persist stable provider keys and attempts, use leases and fencing for replica ownership, prohibit unsafe fallback after ambiguity, and reconcile pending outcomes through signed webhooks or polling. Refunds lock the original payment before reserving balance and stay with the original provider; compensation is an idempotent refund action rather than a distributed rollback.

I tested the actual PostgreSQL, Redis and Kafka boundaries with Testcontainers, including concurrency and crash/retry cases. I also measured API latency separately from business completion. A 15-minute local soak completed without HTTP errors, while stress/spike and a Kafka outage exposed latency failures that I kept in the reports. SQL profiling revealed contention from API-key usage writes; I coalesced that approximate telemetry without caching authentication. A paired PostgreSQL CPU-limit experiment reduced local mixed p95 about 20%. Those are bounded laptop results, not production throughput claims, and three-replica autoscaling could not be validated locally.”

## Deep-dive map

| Topic | Evidence to discuss |
|---|---|
| Idempotency | Merchant/key constraint, fingerprint, rollback then replay; [four boundaries](idempotency.md). |
| Outbox / at-least-once | Acknowledgement crash window, stable event identity, failed head ordering; [outbox](outbox.md). |
| Kafka ordering / consumer dedup | paymentId versus refundId, topics/partitions, DB transition guards. |
| Redis admission | Lua counter/expiry, fail-closed new creates, replay exception. |
| Retry / circuit breaker | Physical attempt records, per-JVM circuits and retry amplification. |
| Timeout / reconciliation | Unknown result, no unsafe fallback, original-provider status and inbox. |
| Refund concurrency | Parent lock, reserved status set, original provider and idempotency. |
| Saga compensation | Internal new refund command, not automatic global rollback. |
| Authentication | Peppered digest, current status/expiry, owned cache/SQL checks. |
| Observability | Correlation/W3C headers, bounded metrics, dependency/probe policies. |
| Kubernetes scaling | Connection/partition/resource budgets and unvalidated HPA scale-up. |
| Performance tuning | Before/after deltas, failed controls, actual rates and dropped iterations; [evidence](performance.md). |

## 48 project questions and model answers

### 1. What is the core problem PayFlow addresses?

Correct payment/refund intent and recovery under client retries, broker redelivery, concurrent replicas and ambiguous provider outcomes. Providers are mock HTTP systems; this is a production-oriented learning backend.

### 2. Why store API idempotency in PostgreSQL instead of Redis?

The merchant/key constraint, payment and outbox commit atomically. Redis expiry or data loss must not permit a duplicate durable charge intent.

### 3. What identifies a repeated payment request?

Authenticated merchant plus Idempotency-Key and a canonical payload fingerprint. Same payload replays; different payload under the key conflicts with 409.

### 4. What if two POSTs both miss the initial idempotency lookup?

The unique constraint picks one committed winner. The losing transaction rolls back its inserts; replay happens in a fresh transaction after rollback.

### 5. Does replay return the exact original payment response?

Payment replay maps current persisted payment state. Refund replay returns the stored creation-response snapshot; GET refund obtains current state.

### 6. Why is IdempotencyService a separate bean?

Spring proxy boundaries create the intended REQUIRES_NEW transaction and let collision recovery run after the failed transaction has ended.

### 7. What happens when Kafka is down after creation?

PostgreSQL retains payment and outbox intent. The API can accept it while publication/processing is delayed; retry bounds and failed-row handling still apply.

### 8. What dual-write failure does outbox remove?

A DB commit followed by failed direct Kafka send would lose intent. Business state and event intent instead commit in one local transaction.

### 9. Why is outbox delivery at-least-once?

A broker may accept before publisher crash/lost acknowledgement. An expired lease republish can deliver the same event again.

### 10. Does Kafka producer idempotence mean exactly-once payments?

No. It does not atomically coordinate PostgreSQL, a restarted publisher and external HTTP provider effects. Durable deduplication and provider keys remain necessary.

### 11. Why use paymentId as the Kafka key?

It groups a payment’s events into one topic partition. Refund events use refundId separately; no global or cross-topic ordering is promised.

### 12. Can more replicas exceed the partition limit?

They can serve HTTP, but active consumer assignments per group are bounded by topic partitions and listener concurrency. Three partitions do not provide unlimited consumer parallelism.

### 13. How is Kafka event deduplication durable?

Processed-event identity is persisted with transactional lifecycle changes. Provider processing additionally uses durable claims/attempts and fenced finalization.

### 14. Why not mark PROCESSING events handled before provider work?

That could discard work after a crash. External work spans short transactions and requires recoverable claims rather than an early permanent success flag.

### 15. What does SKIP LOCKED solve?

Workers skip rows another worker is claiming and select independent eligible work. Ordering eligibility still blocks a newer aggregate event behind an older unpublished head.

### 16. What is a lease?

A database-clock ownership interval enabling recovery after a worker dies. Expiry is not proof that an external request did nothing.

### 17. What is a fencing token?

A completion must match the current claim token; an expired old owner cannot overwrite a newer claim. It cannot cancel an already-sent HTTP request.

### 18. Why avoid DB transactions around provider calls?

Network calls can be slow or uncertain. Short claim/finalization transactions avoid holding financial locks/connections for the entire HTTP interval.

### 19. Why is a timeout not FAILED?

The provider may have charged before the response was lost. PayFlow keeps PENDING_CONFIRMATION and reconciles the original provider.

### 20. What enables a safe provider retry?

The same durable provider request key and persisted attempts/results. Real provider integrations must verify replay scope and retention, not assume mock guarantees.

### 21. When is failover allowed?

Only classified safe failures with no earlier ambiguous attempt. Unknown charge outcomes and business declines do not justify charging through another provider.

### 22. How do retry and circuit breaker interact?

Retry wraps a per-provider circuit breaker, so each permitted physical call is recorded and circuit-checked. Retries are bounded and amplify calls.

### 23. Are circuit breakers shared across replicas?

No, their state is per JVM/provider. Durable workflow ownership is shared in PostgreSQL, but circuit state is not distributed consensus.

### 24. How do lost webhooks recover?

Scheduled payment/refund status polling claims due work and validates provider outcomes. Exhausted/unresolved work stays visible rather than being assumed failed.

### 25. How is a webhook authenticated?

Provider-specific hexadecimal HMAC-SHA256 over exact raw bytes, constant-time comparison, then contract/reference validation and durable inbox deduplication.

### 26. Does the webhook signature prevent every replay?

No. It authenticates bytes; durable event identity and payload checks handle duplicates. A general timestamp anti-replay scheme is not implemented.

### 27. How do concurrent refunds avoid exceeding the charge?

Lock the parent payment and subtract refunds in CREATED/PROCESSING/SUCCESS/PENDING_CONFIRMATION before atomically reserving another refund.

### 28. Why reserve an uncertain refund?

It may already have occurred externally. Releasing its amount before authoritative failure could allow an over-refund.

### 29. Why must a refund use the original provider?

Only that provider owns the recorded charge transaction. Payment fallback does not make a different provider able to refund it.

### 30. What is compensation in this project?

An internal idempotent command creates a refund for available balance. It is a new business action, not XA rollback or a general Saga workflow engine.

### 31. Why not XA/2PC?

External HTTP providers do not participate in the project’s distributed transaction. Local transactions plus durable intent and compensation accommodate external uncertainty.

### 32. Why is Redis failure treated differently for cache and rate admission?

A cache miss can safely use PostgreSQL. Lost shared admission coordination fails new creation closed with 503; committed replays are still checked.

### 33. How is rate limiting shared?

One merchant-key Redis Lua script atomically increments, sets expiry and returns admission/Retry-After. Step 15 verified 10 accepts and 5 rejects across two Pods.

### 34. Can cached data leak another merchant’s payment?

GET checks the cached response merchant as well as owned SQL lookup on misses. Cross-merchant resources return 404; financial decisions use owned locked DB state.

### 35. Is the cache strongly consistent?

No. After-commit eviction helps, but failure or a concurrent fill can leave an old value until TTL. No financial decision trusts cache state.

### 36. How are API keys stored?

Lookup prefix and peppered HMAC digest, not the raw secret. Status/expiry/digest are verified per request; issuance uses an owner-only one-time file.

### 37. Does usage write coalescing delay revocation?

No. Fresh credential checks remain per request. Only approximate last_used_at telemetry writes are limited to a default 60 s cadence.

### 38. What does 201 mean for a payment?

Durable payment intent was created, not that provider charging succeeded. Later state/event processing establishes completion.

### 39. What is locally strong versus eventual?

Payment/idempotency/outbox and refund reservation are PostgreSQL transactions. Broker delivery, external provider completion, reconciliation and Redis views are eventual.

### 40. What does observability cover?

Request/workflow metrics, bounded tags, outbox age/backlog, retries/circuits, cache/limiter, correlations and W3C trace propagation into Kafka. Local OTEL uses a debug exporter.

### 41. Why separate readiness and liveness?

A live process should not restart just because a dependency is down. PostgreSQL gates readiness; aggregate health also reports dependencies, and liveness stays independent.

### 42. Why real Testcontainers instead of H2 and pure mocks?

PostgreSQL locking/uniqueness/SQL, Redis Lua/expiry and Kafka partitions/redelivery are actual correctness mechanisms. Focused mocks cannot establish those semantics.

### 43. What did performance testing find?

Credential audit row-write contention and local PostgreSQL CPU throttling. Two measured changes were retained; high-concurrency failures and warmup sensitivity remain documented.

### 44. What before/after result is safe to quote?

A local primed read comparison p95 36.04→14.15 ms, plus local PG burst-limit mixed p95 53.05→42.49 ms. Cite rates, hardware and single-run caveats; do not imply a universal throughput gain.

### 45. What did the soak and JFR establish?

15 minutes at 2 HTTP/s had zero errors/drops/OOM; a 60 s steady JFR found diffuse CPU and one 645.4 ms GC pause. It is not proof against all leaks or justification for arbitrary GC/pool tuning.

### 46. How did Kafka outage testing behave?

All 180 accepted payments eventually succeeded after recovery; sampled backlog peaked at 52. API p95 1261.29 ms missed the target, so durability passed while latency did not.

### 47. Was HPA performance validated?

Configuration exists, but three-replica/HPA validation could not be completed on the local Mac environment. Two ready replicas and zero lag do not prove autoscaling under load.

### 48. What are the main production gaps?

Real providers/settlement, HA managed dependencies, cloud/TLS/IAM/secrets/backup design and replay operations. No PCI handling, chargebacks, admin UI or production capacity proof exists.

## System-design follow-ups: current versus future

| Follow-up | Current implementation | Future design to evaluate |
|---|---|---|
| How would you scale to 100k TPS? | No evidence of this capacity; bounded two-Pod laptop measurements. | Define traffic/latency/correctness targets, benchmark each bottleneck, shard ownership, partition events, budget DB/provider capacity and admission before claiming TPS. |
| How would you make it multi-region? | One PostgreSQL authority and local Kafka/Redis; no multi-region deployment. | Decide home-region ownership and failover fencing, cross-region idempotency and replication lag; avoid concurrent charges during split-brain. |
| How would Stripe/Razorpay change it? | Mock HTTP contracts and durable simulated ledgers. | Sandbox adapters, authentic signing/timeout classifications, key-retention contracts, rate limits and settlement reconciliation; preserve unknown-outcome rules. |
| How would you handle settlement? | Payment/refund workflow only, no settlement ledger. | Import provider reports, double-entry ledger design, matching/discrepancy cases and auditable correction workflows; separate API success from settled funds. |
| How would you migrate polling to CDC? | DB outbox polling with leases and stable identity. | Connector offset/snapshot strategy, schema compatibility, cutover dedup and old publisher retirement; CDC does not remove at-least-once consumers. |
| How would you retain billions of event IDs? | Durable dedup rows, no automated lifecycle/archiver. | Choose replay horizon, partition/archive storage, preserve uniqueness within retention guarantees; do not delete IDs while old events may replay. |
| How would you archive outbox rows? | Active/failed/published rows kept; failed rows can block successors. | Archive acknowledged terminal rows under safe retention with diagnostic indexes; retain/replay failed intent deliberately. |
| How would you shard PostgreSQL? | One database, merchant-owned records and parent-payment locks. | Merchant/home-shard routing, enforce idempotency and refund locks within shard, route event ownership and handle cross-shard reporting separately. |
| How would you improve autoscaling? | CPU HPA configuration, three-replica performance unvalidated. | First validate rollout/headroom; evaluate lag/age-based KEDA or custom metrics with partition/provider/connection caps and realistic startup times. |

Answer future questions as hypotheses with trade-offs, not features already delivered. Practice tracing one normal and one response-loss payment using [architecture diagrams](architecture.md) and [failure sequence](failure-handling.md).
