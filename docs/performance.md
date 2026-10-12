# Performance evidence

All numbers below come from [the Step 15 baseline](../performance/BASELINE.md) and [optimization report](../performance/OPTIMIZATIONS.md), measured 11 October 2026. The complete reports preserve failures, dropped work, profiling overhead, warmup and power interruptions. These are local measurements, not capacity promises.

## Environment and workload

8 GB Apple Silicon Mac, eight CPUs; Docker VM ~3.82 GiB/eight CPUs; single Kind node. Java 21, Spring Boot 3.5.16, k6 2.3.0; two application Pods, each limited to 1 CPU/1 GiB and Hikari 10. PostgreSQL 17.6, Redis 7.4.2 and one Kafka 3.9.1 broker shared the VM with k6 and mock providers. Three partitions, replication/minISR one. Isolated performance DB and Redis DB 1; Kafka topics/groups shared sequentially while normal consumers were paused.

Scripts cover smoke, read, creation/completion, 70% read/20% create/10% list mixed traffic, list sizes 20/50/100 and deep offset, explicitly cold/warm fixture reads, refunds, identical/conflicting idempotency, ownership, shared limiting, provider failures, stress/spike and soak. Financial checks inspect durable state and mock ledgers, not just HTTP success. [Reproduction guide](../performance/README.md).

## Representative results

HTTP RPS includes GET completion polling when enabled. Latencies are milliseconds. Offered rate and achieved throughput differ; ramp averages are not sustainable capacity.

| Workload | Actual HTTP RPS | p50 | p95 | p99 | HTTP errors | Interpretation |
|---|---:|---:|---:|---:|---:|---|
| Primed read baseline, 20/s for 60 s | 20.01 | 5.31 | 36.04 | 78.63 | 0% | Both replicas served traffic; zero drops. |
| Primed read after audit change | 19.98 | 5.00 | 14.15 | 49.04 | 0% | Zero drops; one local comparison. |
| Mixed baseline, 10/s for 60 s | 10.02 | 8.12 | 73.67 | 157.91 | 0% | Passed targets. |
| Early post-audit mixed | 9.75 | 30.28 | 435.42 | 1187.32 | 0% | Failed read/create latency targets. |
| Separately warmed one-core mixed control | 10.02 | 6.30 | 27.91 | 65.82 | 0% | Shows sensitivity to warmup/host context. |
| Fifteen-minute mixed soak, 2/s | 2.00 | 32.92 | 169.85 | 418.36 | 0% | Zero drops/OOM/restarts; sampled pending connections zero. |
| Baseline stress ramp | 35.47 | 159.96 | 1150.66 | 2196.97 | 0% | Aborted on p99 guard; target 60-VU phase not reached. |
| Baseline spike | 50.48 | 28.98 | 882.68 | 2167.11 | 0% | Aborted; not sustainable capacity. |

Baseline creation offered 2 payments/s for 60 s: all 120 accepted payments completed. API p50/p95/p99 **21.51/127.16/220.71 ms**, business completion **1060/1586.8/1801.19 ms**. After audit change: 121 completions; API **21.45/104.37/186.56 ms**, completion **1037/1559/1631.2 ms**. Completion polling every 500 ms quantizes observations; these are simulated provider times.

Finite 198-ID bursts verified exactly 198 cache misses versus 198 hits, but warm p95 was worse (85.97 versus 70.16 ms). No cache-speedup or sustainable 225 RPS claim follows. The soak hit ratio was 67.41% with TTL expiry, zero fallback. Deep-page sleep-interrupted failure remains recorded; its later control had p95 88.37 ms and one dropped iteration on a small growing dataset, not proof at millions of rows.

## Two retained optimizations

1. **Coalesce API-key usage telemetry writes every 60 s.** Baseline concurrent calls serialized on a credential row: nine transaction-ID waits and one tuple wait in a snapshot. Primed baseline 1201 requests changed 1201 rows; after change 1199 requests changed one row, while still issuing 1199 conditional UPDATE statements. Authentication/status/expiry are freshly checked per request. The trade-off is approximate last-used telemetry. Read p95 36.04 → 14.15 ms, p99 78.63 → 49.04 ms in this low-concurrency comparison; mixed/stress regressions prevent a general throughput-gain claim.
2. **Local PostgreSQL burst limit 500m → 1 CPU.** Same API Pods, mixed 40/s for 60 s after full warmup: achieved 39.91 → 39.98 HTTP/s; p50 6.71 → 5.29, p95 **53.05 → 42.49** (~19.91% lower), p99 **130.35 → 112.66** (~13.56% lower), zero errors; drops 5 → 2. Aggregate throttled time 4.296 → 0.854 s while CPU work was similar. The later sample included 198 cache misses, and the dataset grew; this is one short local trial, not universal database sizing.

Increasing application CPU to two cores was rejected for inconsistent results and unequal aborted windows. No arbitrary Hikari, GC, thread, partition, TTL or index tuning was retained.

## Kafka recovery and JFR

Broker absent 23.40 s before restore; startup another 12.16 s. 180 POSTs accepted at ~2/s, zero HTTP errors; all 180 eventually SUCCESS. Sampled backlog peaked at 52, oldest age 24.82 s. Drain observed 44.95 s after broker Ready (ongoing arrivals/cadence included). API p95 1261.29 ms failed the 500 ms target: durability passed, latency did not.

A 60 s steady JFR during the soak found diffuse CPU samples, 56 PostgreSQL socket reads, and one 645.4 ms GC pause. No sampled Hikari acquisition parks; idle background parks do not justify bigger pools. Sampled memory grew during warmup/diagnostics without OOM, not proof against all leaks. The first profile's traffic aborted early and is retained separately. Defaults stayed unchanged.

## Scaling limitation

**three-replica/HPA validation could not be completed on the local Mac environment**. Kubernetes manifests/HPA configuration exist, but full three-replica autoscaling validation was limited by local resources and slow startup/probe restarts. No OOM explanation was established. Do not report HPA scale-up as performance-validated or conceal failed stress/spike targets.

Final Step 15 correctness: 271 tests passed; final generated-workload DB audit found no duplicate charges, excess reservations, unfinished operations or unpublished/unprocessed events. This does not measure real-provider settlement or production HA.
