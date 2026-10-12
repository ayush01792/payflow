# Step 15 files

Project location: repository root (`payflow/`).

Modified existing files:

- `.gitignore`: local result/profile/cache exclusions.
- `README.md`: Step 15 scope and links.
- `src/main/resources/application.yml`: configurable API-key usage telemetry interval.
- `src/main/java/com/payflow/payment/security/ApiKeyUsageAudit.java`: bounded atomic timestamp writes.
- `src/test/java/com/payflow/payment/security/MerchantSecurityIntegrationTest.java`: coalescing/refresh/revocation regression.
- `src/test/java/com/payflow/payment/observability/ObservabilityIntegrationTest.java`: await asynchronous listener timer/log completion.
- `k8s/local/postgres/statefulset.yaml`: measured CPU burst limit 500m → 1; memory/data unchanged.
- `k8s/local/observability/prometheus.yml`: optional performance namespace scrape target.
- `observability/grafana/payflow-dashboard.json`: performance grouping, pool acquisition mean and scraped-target panels.

Added files:

```text
payflow/performance/
  BASELINE.md
  Dockerfile.profiling
  ENVIRONMENT-PLAN.md
  FILES.md
  JFR.md
  OPTIMIZATIONS.md
  README.md
  k6/cache-read.js
  k6/common.js
  k6/idempotency.js
  k6/ownership.js
  k6/payment-create.js
  k6/payment-list.js
  k6/payment-mixed.js
  k6/payment-read.js
  k6/rate-limit.js
  k6/refund.js
  k6/seed.js
  k6/smoke.js
  k6/soak.js
  k6/spike.js
  k6/stress.js
  k8s/configmap-patch.yaml
  k8s/deployment-patch.yaml
  k8s/hpa-patch.yaml
  k8s/kustomization.yaml
  k8s/metrics-service.yaml
  results/.gitkeep
  scripts/cache-compare.py
  scripts/collect-results.sh
  scripts/collect.py
  scripts/compare-cpu.py
  scripts/compare-postgres-cpu.py
  scripts/compare-replicas.py
  scripts/environment.py
  scripts/kafka-outage.py
  scripts/ownership-rate.py
  scripts/provider-modes.py
  scripts/run-kubernetes.py
  scripts/seed-data.sh
  scripts/summary.py
  scripts/verify-correctness.py
```

Generated JSON, logs, fixture UUIDs and JFR recordings live in `performance/results/` and are ignored. Credentials remain in owner-only files under `secrets/`; they are not part of the source tree. `pom.xml`, payment/refund domain models and migrations V1–V8 are unchanged.
