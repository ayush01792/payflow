# Evidence-based optimizations

## 1. Coalesce best-effort API-key usage writes (measured)

**Problem:** every authenticated request updates one `merchant_api_keys` row. A merchant's concurrent requests serialize on that row even when its payment reads hit Redis.

**Evidence:** the baseline stress ramp stopped at HTTP p99 2196.97 ms. A live PostgreSQL snapshot showed nine transaction-ID lock waits and one tuple lock wait; later the original usage UPDATE had 5978 calls, 486245.44 ms total execution time and 81.34 ms cumulative mean. Credential SELECTs remained separate and were not cached. Spike also stopped safely at p99 2167.11 ms. These are local contention findings, not production capacity claims.

**Change:** the usage UPDATE now writes only when `last_used_at` is null or older than 60 seconds. PostgreSQL rechecks the predicate after a concurrent updater, providing one shared cadence across replicas. The small audit transaction, active-status predicate and existing best-effort failure handling remain. Authentication still queries key/merchant status and expiry on every request; revocation has no cache delay.

**Trade-off:** last-used telemetry becomes approximate by up to one interval while a key is continuously used. This is not a financial ledger or authorization decision. `API_KEY_USAGE_AUDIT_INTERVAL_SECONDS=0` restores per-request writes; negative values fail startup. This change does not weaken idempotency, refund reservation or outbox leases.

**Correctness:** the regression checks initial timestamp, coalescing, stale timestamp refresh and immediate revoked-key rejection. The full suite passed 271 tests, zero failures/errors/skips.

**Write deltas:** the primed baseline read sent 1201 requests and updated 1201 credential rows; the primed audit read sent 1199 requests and updated one credential row. It still executed 1199 conditional UPDATE statements, so this removes write/row-lock work, not the JDBC round trip.

**Identical offered workloads:** baseline and audit use two Pods with 1 CPU/1 GiB limits each, the same 198-ID read corpus, and the same k6 scripts/rates. Startup/JIT age, growing synthetic dataset and shared-VM variation differ; single runs do not establish production causality.

## Other candidates

No pool, thread, Kafka partition, TTL, index or GC tuning has been applied. The second retained optimization is the measured local PostgreSQL burst CPU limit described below. The strict 198-ID warm-cache burst was slower than the cold burst despite verified hits; short shared-VM runs are noisy and authentication still touches PostgreSQL, so this does not justify changing Redis TTL.

References: [PostgreSQL Read Committed predicate rechecks](https://www.postgresql.org/docs/17/transaction-iso.html), [JDK 21 jcmd/JFR](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html).

## Steady JFR baseline

A second 60-second recording was captured during the warmed 2/s mixed soak using a restricted, same-UID ephemeral diagnostic container. The normal JRE app was not restarted or replaced. 373 Java execution samples were collected; CPU leaf samples were diffuse. PostgreSQL socket waits and background idle parks are retained in the analysis; idle waits are not request contention. No sampled Hikari acquisition parks were identified. GC pauses in this steady recording: 645.41 ms. This lower-load profile does not establish a high-load CPU hotspot and does not justify pool/thread/GC tuning.

## Audit comparison results

| Workload | Baseline HTTP RPS / p50 / p95 / p99 ms | Audit HTTP RPS / p50 / p95 / p99 ms | Outcome |
|---|---|---|---|
| Primed reads, 20/s, 60 s | 20.01 / 5.31 / 36.04 / 78.63 | 19.98 / 5.00 / 14.15 / 49.04 | pass |
| Creates 2/s, 60 s + completion polls | 5.92 / 13.74 / 94.76 / 208.11 | 5.54 / 11.22 / 70.91 / 138.09 | pass |
| 70/20/10 mixed, 10/s, 60 s | 10.02 / 8.12 / 73.67 / 157.91 | 9.75 / 30.28 / 435.42 / 1187.32 | threshold failed / aborted |
| 5/15/30/60 VU ramp | 35.47 / 159.96 / 1150.66 / 2196.97 | 24.76 / 117.79 / 1000.77 / 2206.94 | threshold failed / aborted |
| 2/20/2 VU spike | 50.48 / 28.98 / 882.68 / 2167.11 | 36.19 / 166.99 / 958.86 / 2532.57 | threshold failed / aborted |

All these comparison runs had zero HTTP errors. Mixed audit missed read/create targets; stress/spike still hit their p99 abort guards, and audit stress stopped earlier (~57 s engine time versus ~76 s baseline). These failures are retained. The write reduction and low-concurrency read improvement justify coalescing approximate usage telemetry, but there is **no demonstrated general high-concurrency throughput gain**. Both audit Pods reached ~950–980m CPU under their one-core limits; the separately warmed CPU-quota comparison below did not justify retaining a larger application limit. No SQL index or pool change is inferred from that CPU ceiling.

Create-only audit: T_API p50 21.45 / p95 104.37 / p99 186.56 ms; T_END_TO_END p50 1037 / p95 1559 / p99 1631.2 ms; all 121 completed successfully. The baseline counterparts were 21.51 / 127.16 / 220.71 ms and 1060 / 1586.8 / 1801.19 ms.

## Warmed one-core control

After explicit 60 s mixed warmup, the same 10/s mixed workload passed at **10.02 HTTP/s**, p50 **6.30**, p95 **27.91**, p99 **65.82 ms**, zero errors. This contrasts with the earlier fresh post-audit mixed failure and demonstrates the importance of warmup/shared-host conditions; both results remain. The warmed one-core stress ramp achieved **75.00 HTTP/s**, p50 **50.25**, p95 **791.54**, p99 **2120.92 ms**, then stopped on its guard after ~86 s engine time. The earlier baseline stress reached 35.47/s, but different warmup/power context prevents a clean general causal attribution from those ramp-wide aggregates.

## Rejected application CPU-quota candidate

Only the isolated application limit changed from 1 to 2 CPU; request 250m, memory 1 GiB, Hikari 10/Pod, Kafka concurrency one and all financial/auth logic stayed unchanged. Both measured modes reported **Copy/MarkSweepCompact (Serial)**; no GC flag was set. The two-core warmup hit the p99 guard after ~22 s rather than the intended 60 s, a calibration limitation. Future helper warmups retain a bounded full minute without suppressing failed thresholds.

| Workload | One core: RPS / p50 / p95 / p99 ms / HTTP error % | Two cores: same metrics |
|---|---|---|
| 10/s mixed, 60 s | 10.02 / 6.30 / 27.91 / 65.82 / 0.000 | 10.01 / 8.71 / 35.34 / 80.89 / 0.000 |
| same stress ramp | 75.00 / 50.25 / 791.54 / 2120.92 / 0.016 | 66.99 / 30.93 / 506.23 / 2046.14 / 0.000 |

The two-core stress run aborted earlier (~65 s versus ~86 s); its target-15-VU iteration p95 was 510.4 ms versus 368 ms, and target-30-VU 3138.15 versus 2078.8 ms. These unequal completed windows and different startup ages prevent a clean maximum-throughput comparison. Extra quota did not establish a consistent benefit, so **the original one-core limit was restored and no application resource change is retained**. Throttling alone is insufficient evidence to raise every limit.

## 2. Give the local PostgreSQL container measured CPU burst headroom

**Problem/evidence:** PostgreSQL reached 457m against a 500m limit while pending outbox/unfinished payments grew. In the controlled 40/s mixed, 60 s run, 76 of 800 CPU periods were throttled, totaling 4.296 s aggregate throttled time. CPU work was 13.777 s.

**Change retained:** only `k8s/local/postgres/statefulset.yaml` CPU limit changes **500m → 1**. CPU request 50m, memory request/limit 64/256 MiB, max_connections 100, shared_buffers 128 MB, work_mem 4 MB and all schemas stay unchanged. This is local resource allocation, not an application algorithm change or a universal production database sizing recommendation. The Docker VM still has eight CPUs; the extra half-core is available only when demanded.

**Controlled result:** same two one-core API Pods and k6 40/s, 60 s mixed workload, with a separate full 60 s warmup for each database quota. Traffic was stopped and durable work drained before database restart; the application Pods were not restarted between A/B. 500m: 39.91 HTTP/s, p50 6.71 / p95 53.05 / p99 130.35 ms, zero HTTP errors, 5 dropped iterations. One core: 39.98 HTTP/s, p50 5.29 / p95 42.49 / p99 112.66 ms, zero HTTP errors, 2 drops. This is **19.91% lower aggregate p95** and **13.56% lower p99**, not a maximum-capacity claim.

One-core throttling: **12/814 periods, 0.854 s**, versus 76/800 and 4.296 s. CPU work remained similar (14.034 versus 13.777 s), so doubling the ceiling did not double CPU consumption in these windows. Credential SELECT mean decreased 0.1473 → 0.0884 ms; populated-list COUNT mean 3.573 → 2.524 ms, from before/after statement deltas. Read p95 37.38 → 26.35 ms; create p95 94.47 → 93.25 ms and p99 224.08 → 160.73 ms.

**Limits/trade-off:** one short paired local test, a naturally growing dataset and shared dependencies; these are modest gains requiring production remeasurement. The one-core run had 198 fixture-cache misses plus 1482 hits, versus 1678 hits/zero misses in the half-core control, so it was not an identical pure-warm read sample; this extra query work is disclosed. More burst CPU can compete with other services. The original limit was restored after the candidate test, then the measured setting was adopted explicitly in source. No pool, index, batching, TTL, partition or GC setting was tuned.

## Final scope and validation

Exactly two optimizations retained: coalesced approximate API-key usage writes and the local PostgreSQL burst CPU limit. Final smoke, refund, warm ownership and durable financial/event checks passed. Normal deployment restored and health verified. The three-replica/HPA experiment could not complete on this machine; no production capacity or HPA scale-up claim is made. See BASELINE.md for failed runs and FILES.md for changed paths.
