# Security model

## Merchant authentication and isolation

Merchant server-to-server requests use `X-API-Key`. Credentials have a `payflow_test_` prefix, lookup prefix and high-entropy secret. PostgreSQL stores lookup metadata and an HMAC-SHA256 digest using a server-side pepper of at least 32 UTF-8 bytes. The raw key is not stored in the database. Credential issuance writes a one-time owner-only file; the bootstrap helper copies it to an ignored local file and removes the temporary remote file.

Authentication checks key/merchant status, expiry and digest on each request; digest comparison uses `MessageDigest.isEqual`. Auth decisions are not cached. Approximate last-used audit writes are coalesced at a default 60 s interval, independently of revocation enforcement. Rotation/revocation uses the existing non-web bootstrap mechanism, not a public key-management API.

Controllers derive merchant identity from `MerchantPrincipal`; a client-supplied merchant field cannot select another tenant. Owned SQL predicates and cached-response merchant checks cover payment retrieval, listings and refund access. Another merchant's resource returns 404. A refund creation first locks the owned parent payment.

## Webhooks and management endpoints

Provider callbacks use `X-Payflow-Signature`: hexadecimal HMAC-SHA256 over exact raw request bytes with the provider-specific secret, compared in constant time. Raw bytes must not be reformatted between signing and sending. Payload size is capped at 16 KiB, contract/reference checks and a durable inbox protect finalization and duplicate callbacks. Signing proves sender possession of the secret; it is not an OAuth identity system or a general anti-replay timestamp protocol.

Public GET health/probe responses omit sensitive dependency details. Merchant endpoints require authentication; unspecified routes default to deny. Local Compose/Kubernetes/observability profiles expose monitoring endpoints for their internal monitoring topology. Mock routes are available only when explicitly enabled. They are unauthenticated development controls and must not be exposed to an untrusted network. Default profiles disable them.

## Deployment and logging

Application containers run non-root, drop Linux capabilities, deny privilege escalation and use a read-only root with writable temporary space. Kubernetes references generated Secrets; Compose sources local environment values. API keys, pepper and webhook secrets are not intentionally logged; correlation/tracing carries identifiers, not credential headers or bodies. Review new instrumentation for leakage rather than claiming automatic redaction of arbitrary future logs.

Ignore files cover `.env`, `secrets/`, local Kubernetes secrets, logs, credentials, target artifacts and JFR/results. Kubernetes Secret base64 encoding is not encryption or an external secret manager. Do not commit rendered local manifests or print resolved Compose configuration containing actual secrets.

## Limits and deployment responsibilities

No full IAM platform, OAuth/SSO, external secret manager, cloud perimeter or real TLS deployment is implemented. Local Kafka is plaintext and single-broker; local data services are not hardened HA infrastructure. Real TLS, network policy, RBAC, secret rotation/backups and provider credential management need a production deployment design. No real PAN/CVV/card-data handling, PCI certification or compliance claim is made. Amount/payment-method simulation uses mock HTTP providers.

Source: [security package](../src/main/java/com/payflow/payment/security/), [route policy](../src/main/java/com/payflow/payment/config/SecurityConfig.java), [Dockerfile](../Dockerfile), [Kubernetes deployment](../k8s/base/deployment.yaml).
