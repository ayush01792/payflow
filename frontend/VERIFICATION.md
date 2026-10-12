# Frontend verification

Validated against the existing local mock-provider backend on 11 October 2026.

- Backend: `mvn clean verify` passed all 271 tests, with no failures, errors or skips. No backend Java code, authentication rules, CORS settings or migrations were changed.
- Frontend: dependency installation, strict TypeScript/Vite production build, formatting checks and all 53 Vitest/React Testing Library tests passed.
- Browser: all six optional Playwright checks passed. The real flow connected a merchant key, listed payments, created a payment, observed SUCCESS through polling, submitted a refund, observed refund SUCCESS and the payment's partially refunded status, checked public health, reloaded a direct route and disconnected.
- Responsive checks passed at 1440, 1024, 768 and 390 pixels. Actual browser captures were visually inspected for desktop, tablet and mobile forms/dashboard, plus payment details and refund dialogs at desktop/mobile sizes. A delayed real request showed the loading skeleton; a nonexistent merchant-scoped resource showed a friendly 404 state without raw backend details. Navigation works through the drawer below desktop width; tables scroll internally without document overflow.
- Pending confirmation was verified using an explicitly controlled browser response fixture. It remained an uncertain result, with slower polling and no fabricated processing timestamps.
- Docker Compose configuration/build/start succeeded. The non-root static frontend and backend became healthy; API proxy, SPA direct routes and security headers were checked. The frontend proxy rejected management paths with 404.
- Vite development proxy and direct routes returned 200 on port 5174; port 5173 was occupied by another project, which was left running.
- Optional frontend Kubernetes Deployment/Service passed strict schema validation. Existing workload counts and HPA were saved before temporarily pausing Kind for Compose validation; existing volumes/data were preserved. The original two backend replicas, infrastructure and HPA were restored, and the added frontend rolled out successfully. Frontend/proxied backend health returned 200, and the management proxy remained blocked. Local port-forwards expose the dashboard at 3001, backend at 8080 and Grafana at 3000.

Two responsive issues found during the first browser run were fixed: intrinsic table widths causing page overflow and the drawer navigation landmark being placed outside its portal. All six checks passed after rebuilding.

The local Desktop credential files were temporarily offloaded by iCloud. Validation used a fresh owner-only temporary demo credential for the existing merchant, without overwriting or committing those files. No credentials were printed, included in screenshots or stored in browser localStorage. Automated traces and videos are disabled.

The dashboard statistics describe only the fetched page. Recent refunds are browser-session history. Provider payment metadata, a global refund list and an authoritative refundable balance are not supplied by the existing payment API and are not invented. Browser-held merchant credentials remain a local-demo limitation. The backend's prior three-replica/HPA validation limitation remains unchanged.

Portfolio screenshots remain manual-capture slots documented in `docs/screenshots/README.md`; generated UI mockups are not presented as application screenshots.
