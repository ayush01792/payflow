# API overview

Base prefix: `/api/v1`. Merchant endpoints require `X-API-Key`; merchant identity is taken from authentication. POST payment/refund requests also require a nonblank `Idempotency-Key` of at most 128 characters. JSON monetary values use decimal amounts, not floating-point business arithmetic. No real card data is accepted.

| Method and path | Auth | Response/meaning |
|---|---|---|
| `GET /api/v1/health` | Public | 200, `{"status":"UP","service":"payflow"}`; simple process endpoint, not dependency readiness. |
| `POST /api/v1/payments` | Merchant + idempotency | 201 new intent with Location; 200 existing payment replay; asynchronous completion. |
| `GET /api/v1/payments/{paymentId}` | Merchant | 200 owned response; 404 unknown/foreign resource; cache can be temporarily stale. |
| `GET /api/v1/payments?page=0&size=20&sort=createdAt,desc` | Merchant | Owned page and totals; size 1–100; sort only createdAt/amount, unique ID tie-breaker. |
| `POST /api/v1/payments/{paymentId}/refunds` | Merchant + idempotency | 202 accepted refund or stored creation-response replay. |
| `GET /api/v1/refunds/{refundId}` | Merchant | 200 current owned refund; 404 unknown/foreign. |
| `POST /api/v1/webhooks/providers/provider-a` | Provider HMAC | 200 ACKNOWLEDGED after validated processing. |
| `POST /api/v1/webhooks/providers/provider-b` | Provider HMAC | Same provider-specific contract and verification. |

## Create and observe a payment

After [merchant bootstrap](local-development.md), examples avoid placing the actual key in process arguments:

```sh
printf 'X-API-Key: %s\n' "$(cat secrets/local-merchant.api-key)" |
  curl --fail-with-body --header @- \
    --header 'Content-Type: application/json' \
    --header 'Idempotency-Key: demo-payment-001' \
    --data '{"userId":"demo-user","amount":100.00,"currency":"INR","paymentMethod":"UPI"}' \
    http://localhost:8080/api/v1/payments
```

Save the returned UUID as `PAYMENT_ID`, then retrieve it:

```sh
printf 'X-API-Key: %s\n' "$(cat secrets/local-merchant.api-key)" |
  curl --fail-with-body --header @- "http://localhost:8080/api/v1/payments/$PAYMENT_ID"
```
 Reusing the same request/key returns that payment, not another charge. A 201 response is not a SUCCESS result; poll status until a terminal result or inspect pending reconciliation. Payment states include CREATED, PROCESSING, PENDING_CONFIRMATION, SUCCESS, FAILED, PARTIALLY_REFUNDED and REFUNDED. Payment methods are defined in [the enum](../src/main/java/com/payflow/payment/entity/PaymentMethod.java).

## Refund

```sh
printf 'X-API-Key: %s\n' "$(cat secrets/local-merchant.api-key)" |
  curl --fail-with-body --header @- \
    --header 'Content-Type: application/json' \
    --header 'Idempotency-Key: demo-refund-001' \
    --data '{"amount":10.00,"merchantRefundReference":"demo-refund-001"}' \
    "http://localhost:8080/api/v1/payments/$PAYMENT_ID/refunds"
```

The payment must be refundable and have a known original-provider transaction. The API reserves balance before external processing. Use GET `/api/v1/refunds/{refundId}` for completion, rather than replay's stored response snapshot.

## Callback contract and errors

Webhook fields: eventId, providerTransactionId, providerReference, status, occurredAt, and applicable amount/currency. Payment statuses accepted: SUCCESS, FAILED, PROCESSING; refund statuses: REFUND_SUCCESS, REFUND_FAILED, REFUND_PROCESSING. See [DTO](../src/main/java/com/payflow/payment/dto/ProviderWebhookRequest.java) and [signature rules](security.md). No public compensation or admin reconciliation endpoint exists. `/mock/**` is local test infrastructure, not a merchant API.

Common errors: 400 malformed/invalid request, 401 missing/invalid merchant key or webhook signature, 404 missing/foreign resource, 409 conflicting idempotency/refund request, 429 rate admission with Retry-After, 503 unavailable creation limiter or callback verification. Financial work can be accepted while Kafka processing is delayed. Actuator liveness/readiness run on the management port in deployment profiles; see [operations](local-development.md).
