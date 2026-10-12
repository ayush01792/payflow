# PayFlow baseline: Step 15

Measured on **11 October 2026 (Asia/Kolkata)**. This report retains failed and aborted runs. Baseline image: `sha256:cd11ebab62fa7dc8a9b23ebcc62800b1ee4364318c2dbb35ab5768428fcd9a21`. No Git commit is available: this project directory is not a Git repository. The normal runtime image was unchanged throughout baseline workloads; the profiling runs used the explicitly separate diagnostic image.

## Environment and scope

8 GB RAM / 8 CPU Apple Silicon Mac, Docker Desktop VM **4106604544 bytes (~3.82 GiB), 8 CPUs**, single-node kind 0.33.0 / Kubernetes 1.36.4. Java Temurin **21.0.12.1+1**, Spring Boot **3.5.16**, k6 **2.3.0**. Both application and mocks share each JVM; k6 and all dependencies share this VM. Disk initially had about 15 GiB free. These are constrained local results, not a production benchmark or external provider capacity.

Two PayFlow Pods: request **250m CPU / 512 MiB**, limit **1 CPU / 1 GiB**, Hikari default **10 connections each** (20 possible across two; 30 across three). JVM MaxRAMPercentage=60, InitialRAMPercentage=25; Java 21 default collector selected Serial (`Copy` / `MarkSweepCompact`) under these container ergonomics, with no custom GC flag. k6 limit 1 CPU/256 MiB. PostgreSQL 17.6: request 50m/64 MiB, limit 500m/256 MiB, max_connections 100, shared_buffers 128 MB, work_mem 4 MB, pg_stat_statements enabled only for this local measurement period. Redis 7.4.2-alpine, ephemeral, performance database 1, payment TTL 300 s. Kafka 3.9.1 single KRaft broker, three partitions per event topic, consumer concurrency one per PayFlow Pod, replication/minISR one.

Normal PayFlow was paused. Measurements used namespace `payflow-performance`, separate PostgreSQL `payflow_performance`, a synthetic `loadtest-performance` merchant and performance-only limit 100000 creates/minute. Redis database 1 separated cache/limiter keys. Existing Kafka topics/group were used sequentially; normal and performance consumers were not run together. This intentionally shares dependencies and is not full infrastructure isolation.

Seeding attempted 200 payments through the real API. Two requests timed out at the client; **all 200 later reached SUCCESS**. The 198 responses with known IDs became fixtures; no direct invalid SQL inserts. Twenty independent amount-100 payments were refunded by 10 each. Dataset grew naturally with create/stress tests. A 10k+ controlled pagination dataset was not attempted within this bounded local validation budget; deep-page results must not be generalized to larger multi-merchant data.

## Targets and measured HTTP results

Targets were defined before running load: read p95<200 ms, create API p95<500 ms, HTTP/unexpected-response errors<1%, no unbounded outbox or sustained pool exhaustion/OOM. Stress additionally aborts when aggregate HTTP p99>2 s after 20 s. These are starter engineering goals, not production SLOs. HTTP aggregates include polling for completion where enabled; creation latency is reported separately below.

| Run | Actual HTTP RPS | p50 ms | p95 ms | p99 ms | HTTP errors % | Dropped iterations | Outcome |
|---|---:|---:|---:|---:|---:|---:|---|
| read-baseline | 20.00 | 4.00 | 12.86 | 27.81 | 0.00 | 0 | threshold pass |
| cold-cache | 225.44 | 5.96 | 70.16 | 170.31 | 0.00 | 0 | threshold pass |
| warm-cache | 135.23 | 14.30 | 85.97 | 150.70 | 0.00 | 0 | threshold pass |
| create-baseline | 5.92 | 13.74 | 94.76 | 208.11 | 0.00 | 0 | threshold pass |
| mixed-baseline | 10.02 | 8.12 | 73.67 | 157.91 | 0.00 | 0 | threshold pass |
| idempotency-baseline | 22.97 | 148.58 | 679.30 | 679.56 | 0.00 | 0 | threshold pass |
| refund-baseline | 4.87 | 17.35 | 572.83 | 1058.99 | 0.00 | 0 | threshold pass |
| stress-baseline | 35.47 | 159.96 | 1150.66 | 2196.97 | 0.00 | 0 | threshold failed / stopped |
| spike-baseline | 50.48 | 28.98 | 882.68 | 2167.11 | 0.00 | 0 | threshold failed / stopped |
| profile-warmup | 18.80 | 10.69 | 217.82 | 1499.70 | 0.00 | 36 | threshold failed / stopped |
| profile-mixed | 14.72 | 283.15 | 1854.35 | 7956.48 | 1.23 | 112 | threshold failed / stopped |

The read baseline was a repeated fixture-ID workload at 20 requests/s; initial requests included cache misses. The separately primed steady run achieved 20.01 HTTP/s, p50 5.31 / p95 36.04 / p99 78.63 ms, zero errors/drops; per-Pod counters advanced 601/600, proving both replicas served traffic. The strict cold and warm rows are **finite 198-request bursts**, not sustainable capacity: four VUs read each ID once. Counter deltas confirmed exactly **198 misses / 0 hits** cold and **198 hits / 0 misses** warm. Warm had a worse p95 in this short run despite eliminating the payment-row fetch. Shared-VM variation and authentication writes remain relevant; this does not justify increasing TTL. Do not claim a measured cache speedup from these two samples.

Creation baseline offered 2 payments/s for 60 s: 120 accepted, 120 successful completions, no HTTP errors. **T_API p50=21.51 ms, p95=127.16 ms, p99=220.71 ms**. **T_END_TO_END p50=1060 ms, p95=1586.8 ms, p99=1801.19 ms**, measured from before POST to terminal SUCCESS with 500 ms polling and a 30 s deadline. Mixed baseline: representative 70% retrieval / 20% new creation / 10% size-20 listing at 10 operations/s for 60 s; not claimed production traffic.

Idempotency batch: ten identical parallel POSTs, one payment ID and one 201; ten differing amounts under another shared key, one 201 and nine 409. Expected 409s were explicitly classified as expected, not hidden HTTP failures. Refund test completed twenty independent refunds successfully; it included creation, retrieval and terminal polling.

## Saturation and resource findings

Stress ramp targets 5→15→30→60 VUs, without think time. It stopped before reaching the 60-VU stage. Actual run-wide HTTP throughput was 35.47/s, p95 1150.66 ms and p99 2196.97 ms, with no HTTP errors. Stage-target 5 VUs: iteration p95 212.9 ms; 15 VUs: 936.5 ms; 30 VUs: 1843.25 ms, p99 2925.35 ms. These are ramp-phase iteration latencies, not per-endpoint fixed-VU capacity. The bounded spike (2→20→2 VUs) also stopped safely at p99 2167.11 ms. Passed 10/s mixed traffic and failed higher-concurrency ramps bracket the observed local target boundary; they do not establish a universal maximum RPS.

Under stress CPU samples reached ~831m / 584m and memory ~540 / 527 MiB. Hikari samples showed active connections up to eight, with sampled pending zero; increasing its pool is not justified. PostgreSQL live activity showed nine transaction-ID lock waits and one tuple wait. The per-request usage audit UPDATE dominated SQL time: 5978 calls, 486245.44 ms total, 81.34 ms cumulative mean; after spike, 7499 calls / 647378.02 ms / 86.33 ms. The credential lookup SELECT remained around 0.31 ms cumulative mean in the later query snapshot. This distinguishes credential lookup from the unnecessary contended telemetry write. These are cumulative query aggregates; later optimization comparisons use isolated before/after deltas.

Outbox pending snapshots remained small during stress while CREATED/PROCESSING payments accumulated in consumer processing; low outbox pending is not proof of zero Kafka lag. After recovery, a Kafka CLI snapshot showed zero lag on all three partitions. That snapshot was taken during the following rolling image change and had one active consumer at that instant; it is not evidence that steady two-Pod assignments used only one consumer. Replica experiments record fresh assignments separately.

Targeted list plans used existing index scans and incremental sorts. Size-20 offset-200 execution was **0.544 ms** on ~1305 merchant rows; size-100 offset-1000 execution was **4.852 ms**. Plans are retained in ignored `query-plans.txt`. No new index or cursor endpoint is justified by this dataset. Source and targeted query counters show warm GET: credential SELECT + audit UPDATE; cold GET adds one owned payment SELECT; populated list pages add page SELECT + COUNT, independent of page size. No entity associations or per-row selects were found for those endpoints; global SQL logging was not enabled. Refund reservation keeps its payment lock and indexed balance aggregation.

## Profiling and limits

The first diagnostic recording lasted 60 s but its 20/s mixed workload aborted after ~22 s at p99 7956.48 ms, 1.23% HTTP errors and 112 dropped iterations. A preceding freshly restarted read warmup also missed its latency threshold and dropped 36 iterations. These runs include cold startup/JIT/classloading and profiling/shared-VM overhead; they are not steady production capacity measurements. An early export was empty because the recording had not finished; the completed recording was recovered before the Pod was replaced.

The recovered JFR had 1085 execution samples, 2029 allocation samples, 401 socket-read events and three GC pauses (62.4, 105.9, 1221.1 ms). CPU leaf samples were diffuse, not a single proven serialization hotspot. PostgreSQL socket events: 322 samples, total 11.64 thread-seconds, maximum 263.9 ms; internal HTTP-provider socket samples included a 2.09 s wait. No Hikari-acquisition parks were identified in the sampled stacks; long background ThreadPark totals include normal idle scheduler/consumer waits and must not be interpreted as request stalls. The later steady recording is reported below; neither recording justifies arbitrary GC tuning. Allocation weights are statistical estimates, not live heap size.

Redis INFO and application cache counters were collected without KEYS/FLUSHALL. A short in-Pod ping probe saw 0–3 ms samples; the first client-disconnect cleanup left its diagnostic process alive temporarily. It was explicitly stopped, and its probe commands are excluded from workload commands/sec interpretation. Future probes use container-side timeout. Redis-local ping latency is not application-network latency.

## Fifteen-minute soak and steady profile

The 15-minute mixed soak completed (901.2 s including command completion), **2.00 HTTP requests/s**, p50 **32.92 ms**, p95 **169.85 ms**, p99 **418.36 ms**, **0 HTTP errors and 0 dropped iterations**. Twenty-two resource snapshots sampled zero Hikari pending connections; threads stabilized at 65 per Pod. Initial working sets ~444/426 MiB rose to ~547/511 MiB across startup/JIT and diagnostics, with fluctuating live heap rather than a monotonically growing retained-heap measurement. No OOM/restart occurred. This short run gives no proof against every long-duration leak. Backlog drained between bursts; async completion and terminal-state checks were sampled separately from API timings.

Cache deltas: **850 hits, 411 misses, zero fallbacks**, **67.41% payment-cache hits** over the representative soak. The 300-second non-sliding TTL and finite 198-ID working set produce periodic expiration misses. This is genuine reuse, not a reason to increase TTL indefinitely. Redis commands/sec during the diagnostic ping interval are excluded.

A successful second JFR captured **60 seconds of continuous steady soak traffic** on the normal JRE Pod using a restricted same-UID diagnostic container, without restarting the app. It had 373 Java execution samples with diffuse leaf methods, 56 PostgreSQL socket reads (1.57 summed thread-seconds, max 69.1 ms), 14 internal provider HTTP socket reads (max 198.7 ms), and one **645.4 ms** GC pause. Long idle ThreadPark events are normal background waits, not evidence to enlarge pools. No Hikari acquisition parks were identified in the sampled stacks. GC overhead is visible but this low-rate recording does not establish a sustained GC bottleneck; defaults remain unchanged. Profiling perturbed the last minute of the soak and is explicitly part of its local measurement context.

## Validation outcome

The full 271-test suite, stable reads/list pagination, 15-minute soak, steady JFR, provider modes, Kafka outage/recovery, ownership, shared limiter, and final smoke/refund checks completed. Three-replica/HPA validation could not complete on this local machine; both startup failures and threshold failures are retained below.

## Correctness validation diagnostics

The first full run executed 271 tests: five failures and one error. Four failed refund assertions plus one refund exception involved the suite's intentionally tight 100 ms provider socket deadline, causing legitimate ambiguous outcomes/retries on this pressured host; production timeout and workflow semantics were not changed. One observability assertion had a genuine async race: committed SUCCESS became visible before the Kafka listener timer was finalized. That timer/log assertion now uses the existing bounded Awaitility check. The targeted rerun passed all 62 tests (48 refund, 14 observability) with unchanged refund deadlines/assertions, unused local broker/monitoring paused and a bounded 1 GiB test JVM. The fresh full rerun passed **271 tests, zero failures/errors/skips**, in 4 min 49 s. No load or Docker build overlaps correctness tests.

The deep-page baseline had p50 23.16 ms / p95 881.89 ms / p99 3410.47 ms at 4.41 HTTP/s and 17 dropped iterations, so it stopped on the p99 guard. Its external kubectl wrapper elapsed time was much longer than k6's 27.88 s measured runtime. A later read of the macOS power log confirmed Idle Sleep at 16:11:25 and Dark Wake Thermal Emergency sleep from 16:12:14 for 1040 s; the affected run is retained as an interrupted environmental/threshold failure rather than index evidence. Ordinary idle sleep was temporarily inhibited for remaining tests; thermal protections were left active. The control rerun completed; its result is reported below and does not justify an index change.

Large intermediate JFR JSON expansions were removed after analysis to conserve disk. The original binary recordings, jfr summary text and compact analysis JSON remain locally under ignored results, allowing reproduction.

## Provider modes (post-audit, same two one-core Pods)

| Mode | Accepted/completed | HTTP RPS | HTTP p50 / p95 / p99 ms | Completion p50 / p95 / p99 ms | HTTP errors % | Dropped |
|---|---:|---:|---|---|---:|---:|
| provider-delay | 10/10 | 2.00 | 13.88 / 81.19 / 91.77 | 539.50 / 603.45 / 610.29 | 0.00 | 0 |
| provider-transient | 10/10 | 2.98 | 12.20 / 60.11 / 74.49 | 1058.00 / 1126.50 / 1151.70 | 0.00 | 0 |
| provider-timeout | 9/9 | 4.52 | 5.88 / 35.99 / 100.93 | 2578.00 / 14711.80 / 15202.36 | 0.00 | 1 |

Modes were applied to every Pod and reset in finally: SUCCESS with 300 ms delay; HTTP_500 on the first call; TIMEOUT with a 3000 ms delay. 500 ms completion polling quantizes observed completion. One timeout iteration was not started/dropped; all nine accepted payments reached SUCCESS. After the transient run, ten logical payments produced 19 mock payment POST calls across 11 durable provider-key records (some fallback); a provider record is not itself a charge. Final outcome-based duplicate-charge and refund/event checks are performed after recovery. These are local mock semantics, not real network/provider service times.

## First three-replica attempt

The fixed-two 40/s mixed run stopped after ~32 s at HTTP p99 2303.98 ms, delivered 26.32 HTTP/s and dropped 424 iterations. The subsequent third Pod did not become Ready within the 300 s rollout deadline; the helper restored two replicas. No fixed-three/HPA latency result is claimed from that failed attempt. Host memory pressure reported 44% available and the Docker node ~2.84/3.825 GiB used at a sample, so the failure is not established as OOM; startup and host/VM timing limitations remain material.

## Controlled Kafka outage under create traffic

The broker was absent for 23.40 s before the restore command; broker startup then took 12.16 s. Traffic continued at 2 creates/s for 90 s: **180 accepted, zero HTTP errors, all 180 eventually SUCCESS**. HTTP p50 109.61 / p95 1261.29 / p99 1756.43 ms, achieved 1.995 HTTP/s. The create-p95 target failed (exit 99); durable acceptance is not a latency pass.

Sampled maximum pending/claimed outbox was **52**, oldest age **24.82 s**; the true peak between samples may be higher. Outbox and payment processing drained **44.95 s after broker Ready** (including ongoing traffic and sample cadence), **57.11 s after restore command**. Final sampled state: zero pending outbox and 180 SUCCESS. No event deletion, manual terminal-status edit or lease bypass was used. Raw recovery timeline is retained in ignored results/kafka-outage-events.json.

## Shared limiter and ownership

A second synthetic merchant was bootstrapped through the existing CLI. Another merchant's fixture GETs returned 404 and its own created/retrieved payment succeeded. The explicit cold-cache ownership test deleted only three fixture keys in Redis database 1. The first run labeled warm had not explicitly re-primed its old fixtures after their 300 s TTL; it is retained as an unprimed ownership control, not proof of the cached authorization path. A separately verified warm check is recorded after explicit owner priming.

With the performance-only limit temporarily set to **10/minute**, 15 concurrent requests from the fresh merchant produced exactly **10 × 201 and 5 × 429**, all Retry-After values positive, all checks passed. Per-Pod POST deltas were **10 and 5**: Pod A 6 accepted/4 rejected; Pod B 4 accepted/1 rejected. This proves one shared Redis window across replicas. Expected policy rejections were classified explicitly; this is a correctness check, not general-throughput capacity. Only this disposable merchant's earlier window was reset before the check. The performance limit was restored to 100000; the normal limit was unchanged.

The deep-page control rerun (page 50, size 20; 5/s, full 30 s) completed: **5.00 HTTP/s**, p50 **20.18** / p95 **88.37** / p99 **715.99 ms**, zero HTTP errors and **one dropped iteration**. It used the larger post-load dataset; it is a control after the power interruption, not a same-time isolated index experiment. No pagination index or cursor endpoint was added.

The post-outage/rate-limit ledger check reported **zero** duplicate merchant idempotency keys, unintended duplicate synthetic request-user IDs (excluding intentional conflicting-idempotency payloads), over-reserved refunds, unpublished outbox events, unprocessed published events, duplicate successful provider charge records and nonterminal payments/refunds. Provider call amplification: delay 10 calls/10 payments; transient 19/10; timeout 10/9; Kafka outage 180/180. These read-only checks cover the generated dataset and durable mock ledgers, not a real payment processor's settlement system. The final check is repeated after remaining CPU/scaling traffic.

The retry's third container logged **411.335 s** to Spring startup, then was stopped after its startup-probe failures (exit 143, not OOM). A 600 s rollout wait cannot make a Pod healthy when its ~300 s startup probe has already restarted it. The failed three-Pod attempt is preserved; probe thresholds were not weakened to manufacture a readiness pass. A brief native activity assertion was used before later controlled measurements to bring the host out of idle; no persistent power setting or thermal protection was changed.

## Final retained-change checks

The explicitly owner-primed warm ownership check, final refund check and final smoke check all exited successfully. The final read-only database audit found zero duplicate merchant idempotency keys, duplicate synthetic user IDs, excess refund reservations, unpublished outbox rows, unprocessed published events, duplicate provider charge records, and nonterminal payments/refunds. Raw evidence remains in ignored results/correctness.json and the ownership-warm-verified, refund-audit and smoke-retained artifacts.

## Restoration and final validation

Normal deployment restored with the tested image sha256:7d8ab3c34cb201073cff67acd6ba7d41c8d3563e06ad6f86969d11506afa529d; both application replicas Ready, zero restarts, health response status UP/service payflow. Payment and refund consumer groups each had zero lag across three partitions with two active consumers. Performance replicas were stopped before normal consumers resumed. Temporary PostgreSQL profiling arguments were removed, local HPA restored to its saved two-replica cap, Kafka/Redis/PostgreSQL/monitoring ready, and the temporary idle-sleep assertion stopped. Data and volumes were preserved.

Final manifest validation: performance 9/9 and local 28/28 resources valid under strict Kubernetes 1.36 schema checks. Python and shell helper syntax passed; dashboard has 25 unique panel IDs; V1–V8 migration hashes unchanged. No Java changes followed the successful 271-test full run. Three replicas and HPA scale-up remain unvalidated on this host; stress/spike target failures are reported rather than hidden.
