# PayFlow merchant dashboard

Responsive presentation layer for the existing PayFlow backend: React 19, Vite, strict TypeScript, Material UI, React Router, Axios, TanStack Query and one Recharts status chart. There is no new backend/BFF, Redux, authentication replacement or card-data form.

## Local development

Node 24 and npm; run the backend on localhost:8080 first. From the repository root:

```sh
cd frontend
cp .env.example .env
npm install
npm run dev
```

Open http://localhost:5173. `VITE_PAYFLOW_API_URL=http://localhost:8080` configures the **development proxy upstream**. Axios uses `/backend` in development; Vite forwards only `/backend/api/v1` to that upstream, stripping `/backend`. The browser stays same-origin, so backend CORS/security configuration is unchanged. See [Vite proxy documentation](https://vite.dev/config/server-options.html#server-proxy).

Docker builds set VITE_PAYFLOW_API_URL=/backend; Nginx forwards that API prefix to the existing backend. Build-time VITE variables are public bundle configuration, never credentials. Runtime PAYFLOW_UPSTREAM configures only the Nginx target. A standalone static deployment with an absolute API origin needs deliberately configured backend CORS and CSP; it is not the default demo setup. Do not use wildcard CORS.

## Connect a merchant

Use the existing bootstrap helper from the repository root:

```sh
./scripts/docker-bootstrap-merchant.sh local-demo-merchant secrets/local-merchant.api-key
```

For Kind use `scripts/k8s-bootstrap-merchant.sh` and its saved Kubernetes credential instead. Enter the resulting key manually at `/connect`; it is validated against an authenticated list request and saved in **sessionStorage**, never localStorage, URLs or source code. Keys are masked by default. Disconnect or switching credentials cancels cached reads, clears Query cache, pending submission retries and local refund history. No credential is logged or put in a VITE environment variable. Session storage remains accessible to scripts in the browser and is not a secure vault.

**This browser credential flow exists only for local portfolio demonstration. Merchant API keys are intended for server-to-server use. A real merchant web application would normally authenticate users to its own backend/BFF and keep PayFlow merchant credentials server-side.** No BFF is implemented here. Restrict the demo to loopback/local access; do not use real merchant/payment credentials. Unrelated browser session storage is left intact.

## Pages and data boundaries

| Route | Behavior |
|---|---|
| `/` or `/dashboard` | Latest 20 loaded payments, current-page statistics/chart, recent five payments, browser-session refunds and public health. |
| `/payments` | Server-side page/size/sort; permitted createdAt/amount sorting, 10/20/50/100 rows, refresh and detail links. |
| `/payments/new` | Validated user/decimal amount/currency/method; asynchronous submission and navigation to detail. |
| `/payments/:paymentId` | Owned detail, status card, creation/current-record timestamps, polling and eligible refund action. |
| `/refunds` | Local recent submissions and full-ID lookup; not a global backend refund list. |
| `/refunds/:refundId` | Authoritative GET refund result, provider fields/failure metadata and polling. |
| `/system` | Public `/api/v1/health`; API responsiveness only, not dependency readiness. |
| `/connect` | Local-demo credential entry; merchant routes redirect here when disconnected. |

PaymentResponse has no provider, provider transaction ID, safe failure metadata or complete refund ledger. The UI does not invent them, intermediate timeline events or a refundable maximum. Backend validation remains authoritative. Stats cover only fetched rows; currencies are never combined into a fake total. Session refunds are bounded to 25 IDs/metadata and refreshed authoritatively on detail; they are not the full database.

## Request safety and polling

A new logical payment/refund gets crypto.randomUUID() as its Idempotency-Key. The immutable payload/key is saved in this tab before POST. Mutations do not automatically retry; a manual retry or remount reuses the saved identity. Failed/unknown submissions lock their original fields. Starting an independent payment/refund requires explicit confirmation because the earlier request might have been accepted. Disconnect/credential switching clears saved retries, so reconnecting must not be treated as recovering an old unknown submission.

Amounts are validated as decimal strings (up to 15 integer digits/4 fractional places) and sent as strings that Jackson converts to BigDecimal, avoiding JS arithmetic on submitted money. Display uses Intl.NumberFormat; dates use Intl.DateTimeFormat with backend timestamps.

Active detail states poll every 2 s; PENDING_CONFIRMATION polls every 10 s and is explained as uncertainty, not failure. SUCCESS/FAILED/PARTIALLY_REFUNDED/REFUNDED stop polling. Background-tab polling is disabled; errors stop periodic detail polling until refresh. Read queries retry transient errors once; mutations never blindly retry. Status remains subject to the backend's eventual cache semantics.

## Docker

Fill the existing root `.env` blank secrets with independent values; no merchant key belongs there. From the root:

```sh
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

Dashboard http://localhost:3001 · backend http://localhost:8080 · Grafana http://localhost:3000. Optional FRONTEND_PORT changes only the dashboard's host port. The multistage Node build runs static assets in non-root Nginx on container port 8080, with SPA fallback for direct routes and /healthz. Read-only root, minimal tmpfs and capability restrictions are configured in Compose. Nginx proxies the API prefix; `/backend/actuator` is blocked. No internal management API is fetched. CSP, nosniff and no-referrer headers cover routes; credentials/results are not cached by the proxy. Frontend health is independent of Grafana/Prometheus.

## Optional Kubernetes

Keep the existing PayFlow namespace/backend first. The optional overlay adds only one lightweight frontend Deployment and a ClusterIP Service:

```sh
docker build -t payflow-frontend:local frontend
kind load docker-image payflow-frontend:local --name payflow
kubectl apply -k k8s/frontend
kubectl -n payflow rollout status deployment/payflow-frontend --timeout=180s
kubectl -n payflow port-forward service/payflow-frontend 3001:80
```

PAYFLOW_UPSTREAM points to `http://payflow:80`, the actual backend Service port. No ingress or management-port exposure is added. Avoid full Compose and Kind stacks simultaneously on a constrained laptop. The backend's prior three-replica/HPA performance limitation remains unchanged.

## Architecture and responsiveness

`api/` owns Axios/types/session storage; `hooks/` owns server state and logical submissions; reusable presentation is in `components/`; payment/refund/dashboard modules are in `features/`; routes are in `pages/` with a shared responsive `layouts/` shell and `theme/` tokens. Routes/chart are lazy-loaded. Query caches are isolated by a random session ID, never by a raw key. No global Redux is needed.

At desktop widths navigation is persistent; below 1200px it becomes a labelled keyboard-accessible drawer. Cards/forms stack and tables scroll inside their container. Labels, text-plus-icon statuses, focus styles, a skip link and accessible copy feedback are included. Validate 1440, 1024, 768 and 390px; stored screenshot instructions are [here](../docs/screenshots/README.md).

## Tests and verification

```sh
npm run build
npm test
```

Vitest/React Testing Library cover status labels, amount formatting, friendly errors without stack traces, validation, API-key session behavior/header scope, terminal polling and immutable/reloaded submission retries. Tests use clearly synthetic credentials only. Backend correctness remains `mvn clean verify` from the root.

Optional real-backend smoke (uses a local mock merchant key; adds one payment/refund):

```sh
npx playwright install chromium --only-shell
PAYFLOW_API_KEY_FILE=../secrets/local-merchant.api-key npm run test:e2e
```

Override PAYFLOW_UI_URL if using a different local dashboard URL. No trace/video capture stores entered credentials. Controlled pending-state browser tests are labelled simulations, not claims about real provider outcomes. No long k6/JFR tests are repeated for this presentation layer.

Completed checks and their limits are recorded in [VERIFICATION.md](VERIFICATION.md).
