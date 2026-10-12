# PayFlow performance engineering (Step 15)

This is a separate, manually invoked k6 suite. It does not run during Maven tests. Use a disposable local environment and synthetic merchant data. This Mac has 8 GB RAM and a roughly 4 GB Docker VM; local results are not production capacity or provider capacity. Providers are mocks in the same JVM.

## Prerequisites and environment

Java 21 and Maven for correctness; k6 **2.3.0**, Python 3 (standard library only), kubectl, and the existing Step 14 kind cluster for measurements. See [the environment plan](ENVIRONMENT-PLAN.md). The isolated overlay uses two Pods, separate PostgreSQL `payflow_performance`, Redis database 1, and a performance-only create limit of 100000/minute. Shared Kafka topics/group mean the normal deployment must be paused: do not run both databases' consumers concurrently. Drain outbox before restoration. This is a sequential local setup, not a multi-environment Kafka isolation design.

```bash
export KUBECONFIG="$PWD/secrets/kind-kubeconfig"
export PAYFLOW_NAMESPACE=payflow-performance
docker build -t payflow:step15-audit .
kind load docker-image payflow:step15-audit --name payflow
python3 performance/scripts/environment.py prepare
bash performance/scripts/seed-data.sh
python3 performance/scripts/run-kubernetes.py smoke.js smoke
python3 performance/scripts/run-kubernetes.py payment-read.js warm RATE=10 DURATION=30s
python3 performance/scripts/run-kubernetes.py payment-create.js create RATE=2 POLL_COMPLETION=true
python3 performance/scripts/run-kubernetes.py payment-mixed.js mixed RATE=10
python3 performance/scripts/run-kubernetes.py stress.js stress
python3 performance/scripts/run-kubernetes.py spike.js spike
python3 performance/scripts/run-kubernetes.py soak.js soak RATE=2 DURATION=15m
python3 performance/scripts/run-kubernetes.py refund.js refund
python3 performance/scripts/run-kubernetes.py idempotency.js idempotency
bash performance/scripts/collect-results.sh after-mixed
```

Scripts accept `BASE_URL`, `API_KEY` or preferably `API_KEY_FILE`, `PAYMENT_ID` or `FIXTURES_FILE`, `RATE`, `DURATION`, `VUS`, `MAX_VUS`, `RUN_ID`, `POLL_COMPLETION`, `POLL_SECONDS`, `COMPLETION_TIMEOUT_MS`, `PAGE`, `PAGE_SIZE`, `PACE_SECONDS`, and JSON `STAGES`. The runner supplies a unique run ID, mounts the credential as a Secret, and writes ignored JSON/logs to `results/`. It rejects credentials in positional settings. Credentials are not printed. Treat generated data and result logs as local artifacts, not checked-in fixtures.

For a directly reachable single instance:

```bash
API_KEY_FILE="$PWD/secrets/performance.api-key" PAYMENT_ID="<owned payment UUID>" \
  k6 run performance/k6/payment-read.js
```

A kubectl port-forward targets one Pod; use the in-cluster runner against ClusterIP for multiple-Pod comparisons. k6 shares the VM with dependencies, so monitor generator CPU and dropped iterations. The runner's Pod has 1 CPU/256 MiB limits. Many connections/VUs are necessary to exercise Service distribution; compare per-Pod HTTP counters to verify it.

## Workloads and interpretation

Smoke: one VU/30 s, health only for smoke, authenticated read/create/retrieve and terminal completion. Read baseline exercises authentication, cache and serialization. Cache comparison uses `cache-read.js`: delete only fixture keys `payflow:payment:<UUID>` from **Redis database 1**, run each fixture once, prime those same IDs, and rerun the exact finite test. Do not label a long loop over one initially cold key a cold-cache test. Authentication continues to hit PostgreSQL on cache hits.

Creation uses unique idempotency keys and user IDs. `create_api_ms` measures the POST response; `completion_ms` measures creation to terminal state when polling is enabled. `business_success` records successful completion versus failure/timeout. Polls are spaced at 500 ms and bounded at 30 s by default. HTTP success does not prove business success. Mixed is a representative **70% reads / 20% creates / 10% size-20 lists**, not a claim about production traffic. Compare list sizes 20/50/100 and deeper pages explicitly when datasets permit.

Refunds consume the seed's last 20 successful payment IDs once per fixture; create fresh fixtures before repeating. Idempotency submits parallel identical payloads (one ID/one 201) and differing amounts (one winner/nine 409); verify database rows too. Rate-limit test requires a new isolated merchant and a deliberately low limit in the performance overlay; expect exactly N accepted plus five 429 with positive Retry-After. Do not run rate-limit configuration against normal PayFlow.

Stress defaults to 5→15→30→60 VUs with a p99>2 s abort after 20 s. Spike is bounded at 20 VUs for this Mac. Soak defaults to 2 mixed iterations/s for 15 min. Stop manually if memory pressure, OOM, sustained pool exhaustion or unbounded backlog appears. Workload thresholds are starting engineering targets: read p95<200 ms, create p95<500 ms, HTTP/unexpected-response errors<1%. They are not production SLOs. An aborted/failed-threshold test is evidence, not a successful capacity claim. All metrics use bounded operation/name tags; UUID URLs are excluded from system tags.

## Measurements, profiling and scaling

Collect before, during and after snapshots. The collector saves PostgreSQL activity/waits/query statistics, Redis INFO stats/clients, Hikari/JVM/provider/cache/outbox metrics, per-Pod cgroup throttling/memory, kubectl top and HPA state. Enable pg_stat_statements in the disposable environment before testing; no global SQL logs. Use targeted EXPLAIN (ANALYZE, BUFFERS) outside heavy load. Query counters are cumulative: compare deltas for each workload. Redis INFO hits include other commands; use PayFlow's cache counters for payment hit ratio. Kafka consumer-group CLI snapshots show lag and assignments; use small CLI heap and avoid frequent JVM spawning.

Use the existing Grafana dashboard and the performance row. Kubernetes replica/CPU-request metrics need kube-state-metrics or equivalent, which is not added here; kubectl HPA/top remains the source for those readings. Observe provider retries, Hikari acquisition/pending, heap/GC/threads, cache, outbox pending/age and Kafka lag, not HTTP timings alone.

Run `python3 performance/scripts/compare-replicas.py` for identical 40/s, 180 s mixed workloads with fixed two, fixed three and HPA two-to-three replicas (`SCALING_RATE` overrides the offered rate for all compared runs). A separately labeled 60 s startup warmup is excluded from the fixed-three comparison (a safety-aborted warmup must not be described as a full minute). Rollout waits are bounded at 600 s; the overlay startup probe permits 60 failures at five-second periods (~300 s); the first measured 300 s third-Pod attempt failed and is retained in the report. On this Mac cap HPA at three rather than pretending six replicas are safe. Record offered/achieved rate, errors and p95/p99, plus start-to-Ready delay and consumer rebalances. HPA scaling alone is not evidence of better latency.

[JFR profiling instructions](JFR.md) use a separate diagnostic image; the normal JRE runtime image stays unchanged. Capture 60 seconds during sustained load and analyze with the local Java 21 `jfr` tool. Large JFR/results are ignored.

Run `python3 performance/scripts/kafka-outage.py` for the local failure experiment and `python3 performance/scripts/provider-modes.py` for small delay/HTTP_500/TIMEOUT comparisons. Controls are always reset. Run `python3 performance/scripts/ownership-rate.py` for the second-merchant checks and exact shared limit (only its synthetic merchant window is reset), then `python3 performance/scripts/verify-correctness.py` after work drains for read-only ledger/event checks.

Controlled Kafka outage: keep moderate create traffic running, scale the **local** broker to zero briefly, measure durable accepted payments and growing pending outbox/oldest age, restore broker, then wait for completion and drain. Always restore in a finally/trap path. Do not benchmark a dead PostgreSQL instance. Provider delay/failure controls are process-local: set/reset every API Pod and distinguish logical payments from physical provider calls.

## Correctness and reports

```bash
mvn clean verify
```

`python3 performance/scripts/environment.py restore` restores the normal local deployment after draining the performance outbox. Stop load before this full Testcontainers suite on a small Mac. The verified run used a 1 GiB test JVM (`-DargLine=-Xmx1024m`) and temporarily paused unused local Kafka/monitoring while Testcontainers supplied its own dependencies; restore those services before measuring traffic. Review [BASELINE.md](BASELINE.md) and [OPTIMIZATIONS.md](OPTIMIZATIONS.md). Only changes supported by measured evidence are eligible; preserve authentication revocation, idempotency, refund reservation, outbox fencing/order and at-least-once handling. No arbitrary pool, GC, thread, partition or TTL tuning.

Official references: [k6 options](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/), [custom summaries](https://grafana.com/docs/k6/latest/results-output/end-of-test/custom-summary/), [PostgreSQL pg_stat_statements](https://www.postgresql.org/docs/17/pgstatstatements.html).

Stress stage labels identify the ramp's **target**, not a fixed concurrency for its entire duration. Custom JSON `STAGES` may contain at most eight stages; the labels and summary thresholds follow those stages. `summary.py` prints measured HTTP aggregates and dropped iterations without interpreting missing data as capacity.

For a short Redis ping-latency probe, execute the timeout **inside** the Pod: `kubectl -n payflow exec deployment/redis -- timeout 3 redis-cli --latency`. Killing the kubectl client alone can leave its remote process running. This measures local Redis ping latency, not application-network or HTTP latency. Exclude probe traffic when interpreting Redis commands/sec.

`python3 performance/scripts/compare-cpu.py` compares the same mixed/stress scripts with one versus two CPU limits per Pod, always restoring one afterward. This is a resource-allocation candidate, not an automatic production recommendation. Java default GC/compiler ergonomics may change with available cores; record the effective collector and extra CPU budget. Bootstrap credentials before measurements: its separate JVM shares the selected Pod's quota. Keep ordinary idle sleep out of benchmark windows; never override hardware thermal protections. Interrupted runs are not capacity evidence.

The HPA diagnostic retains its full **bounded 180 s** window with `ABORT_ON_LATENCY=false`, so a Pod's startup can be observed after an early latency-target miss. The p99 threshold still fails the result; it is not relaxed or reported as a pass. Other workloads retain the default early abort. Watch memory/backlog and stop the diagnostic on unsafe resource pressure. Compare time windows and startup separately rather than treating aborted fixed-replica runs as equal-duration steady capacity.

The retained local PostgreSQL limit is one CPU (formerly 500m), based on the paired quota/statement/throttling evidence in OPTIMIZATIONS.md. To reproduce that A/B, `python3 performance/scripts/compare-postgres-cpu.py` temporarily tests 500m then one CPU, drains work before each restart and restores the starting limit. It does not send benchmark traffic while PostgreSQL is down. Memory, connections, pool sizes and financial logic are unchanged.

Verified macOS full-suite settings (use your installed Java 21/Maven paths):

```sh
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
MAVEN_OPTS=-Xmx512m mvn -B -ntp -Dapi.version=1.44 -DargLine=-Xmx1024m clean verify
```

Final suite: 271 tests, zero failures/errors/skips. See [changed-file inventory](FILES.md). Generated load results and JFR recordings stay ignored; secret values are not included in reports.
