# Local development and operations

## Docker Compose: preferred first run

Use Docker Desktop/Engine with Compose v2. Java 21 and Maven are needed for host-side tests/builds; the multi-stage Dockerfile supplies its own build tools. Run commands from the repository root. Do not run complete Compose and Kind stacks simultaneously on a constrained laptop.

```sh
cp .env.example .env
chmod 600 .env
```

Fill all blank secrets before starting: POSTGRES_PASSWORD, API_KEY_PEPPER, PROVIDER_A_WEBHOOK_SECRET, PROVIDER_B_WEBHOOK_SECRET and GRAFANA_ADMIN_PASSWORD. Use independent `openssl rand -hex 32` outputs; preserve an existing `.env` instead of overwriting it. No default credentials are shipped. Then:

```sh
docker compose config --quiet
docker compose up --build -d
docker compose ps
curl --fail http://localhost:8080/api/v1/health
./scripts/docker-bootstrap-merchant.sh local-demo-merchant secrets/local-merchant.api-key
```

Wait for dependency health and app startup; bootstrapping can take time on a small VM. The helper uses the existing non-web bootstrap profile, saves the one-time key with owner-only permissions, removes its remote temporary file and refuses to overwrite an existing local key. Reuse the saved file for [API examples](api.md); never paste the key into a repository, screenshot or shared log.

Prometheus: localhost:9090; Grafana: localhost:3000 with credentials from `.env`. Application/monitoring host bindings are loopback. Port overrides are in `.env.example`; host Kafka uses 19092 by default. Management is internal port 9091, not the merchant API port. Docker volumes persist PostgreSQL/Kafka/monitoring state. `docker compose stop` preserves data; `down -v` destroys volumes and is not routine cleanup.

## Java/Maven development

Supply PostgreSQL, Redis, Kafka, API_KEY_PEPPER and webhook configuration through environment variables or the documented profiles before `mvn spring-boot:run`. `.env` is Compose input, not automatically a Spring Boot dotenv loader. Flyway V1–V8 creates the current schema; do not edit applied migrations. Full tests use their own Testcontainers instances:

```sh
java -version
mvn clean verify
```

See [testing](testing.md) for Docker socket and memory options. Docker image builds skip tests; run verification separately before accepting source changes.

## Kubernetes quickstart

Kind 0.33.0, Kubernetes 1.36.4 node pinned by `k8s/local/kind-config.yaml`, kubectl and Docker. Preserve an existing cluster/credentials; the create command is for a fresh checkout. More than 4 GiB VM memory is helpful; the measured 8 GB Mac could not complete three-replica validation.

```sh
docker build -t payflow:local .
docker build -t payflow-otel-collector:local observability/otel
mkdir -p secrets
kind create cluster --name payflow --config k8s/local/kind-config.yaml   --kubeconfig "$PWD/secrets/kind-kubeconfig"
chmod 600 secrets/kind-kubeconfig
export KUBECONFIG="$PWD/secrets/kind-kubeconfig"
kind load docker-image payflow:local --name payflow
./scripts/k8s-load-images.sh
cp k8s/local/secrets.env.example k8s/local/secrets.env
chmod 600 k8s/local/secrets.env
```

Fill every blank local Secret value with an independent random value. No actual values belong in documentation. Validate without printing rendered Secrets:

```sh
kubectl kustomize k8s/base >/dev/null
kubectl kustomize k8s/local >/dev/null
kubectl apply -k k8s/local
./k8s/local/install-metrics-server.sh
kubectl -n payflow rollout status deployment/payflow --timeout=600s
kubectl -n payflow get pods
./scripts/k8s-bootstrap-merchant.sh kubernetes-demo-merchant secrets/kubernetes-merchant.api-key
kubectl -n payflow port-forward service/payflow 8080:80
```

The final port-forward stays running. From another terminal use health/API calls; substitute the Kubernetes key file for the Compose key file. Helpers run from the root and honor PAYFLOW_NAMESPACE where applicable.

The base has CPU HPA 2–6 replicas at 70% of CPU request, PDB, probes and graceful termination; the local overlay caps HPA at two. Metrics Server supports HPA; Prometheus supports observation. Local PostgreSQL/Kafka are one StatefulSet each; Redis is ephemeral, not HA. PDB is a voluntary-disruption policy, not zero-downtime proof. Local PostgreSQL's measured CPU ceiling is one core. HPA scale-up performance remains unvalidated. Detailed earlier setup/troubleshooting is preserved in [development history](development-history.md).

## Monitoring and failure recovery

Actuator liveness is dependency-independent; readiness includes PostgreSQL. Aggregate health additionally reports dependencies such as Redis/Kafka. Outbox backlog age, failed rows, Kafka lag/DLT, pending-confirmation age, retries/circuits, refund reservations, cache/limiter failures, pool metrics and request latency are distinct signals. Grafana dashboards are [provisioned here](../observability/grafana/); [Prometheus alerts](../observability/prometheus/alerts.yml) and [OTEL config](../observability/otel/) remain in source. Local Collector uses a debug exporter, not a persistent hosted tracing UI.

If Kafka returns, wait for lag/outbox/terminal-state recovery; do not delete durable event rows to make a metric green. FAILED outbox and DLT records need diagnosis; there is no admin replay console. If a provider result is uncertain, query/reconcile rather than send a fresh charge to another provider. Preserve volumes, one-time credentials and original provider references during troubleshooting. Never render secret-bearing configuration into public logs.
