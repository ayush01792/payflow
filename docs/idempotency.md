# Idempotency: four duplicate boundaries

| Layer | Durable identity | Duplicate problem solved |
|---|---|---|
| Payment API | `(merchant_id, idempotency_key)` in PostgreSQL | A merchant retries POST after losing the HTTP response. |
| Kafka consumer | `eventId` in processed-event storage | Broker redelivery or outbox republish after acknowledgement uncertainty. |
| Provider payment | Stable provider request key persisted with orchestration | Retry or worker recovery after external HTTP uncertainty. |
| Refund | `(payment_id, idempotency_key)`, unique merchant refund reference per payment, stable provider refund key | Duplicate refund submission and external refund retry. |

## Payment API

The server derives merchant identity from the API key. A length-prefixed SHA-256 fingerprint covers merchant, user, normalized decimal amount, currency and payment method. Same key/same fingerprint returns the existing payment (HTTP 200, current persisted state); conflicting payload returns 409. First creation returns 201. Validation rejects blank/oversized keys. A replay check precedes rate-limit admission; an additional replay check covers a concurrent commit when admission fails.

Both requests may initially miss a record. PostgreSQL uniqueness selects the winner; the loser's entire payment/idempotency/outbox transaction rolls back. Replay runs through a separate Spring transactional bean after that failed transaction has ended. Redis expiry/eviction cannot remove this correctness record. No automatic idempotency cleanup/retention policy is implemented.

## Consumer deduplication

For CREATED transitions, inserting the processed-event claim, payment row lock/state change and next outbox intent share one DB transaction. A rollback removes the claim so Kafka retry can try again. PROCESSING provider work needs more than a blanket early dedup flag: durable workflow claims, attempts and fenced finalization allow recovery without forgetting external work. Record acknowledgement happens after handling; Kafka offsets and PostgreSQL are not one distributed transaction.

## Provider boundary

An internal event ID cannot stop an external provider from charging twice. Provider requests use a stable durable key, and the mock HTTP ledger returns the prior outcome on reuse. Recorded business results can be recovered without another call. Unknown outcomes retain original provider/request identity. Failover is forbidden once an earlier attempt may have produced a charge. A real integration must verify the provider's key scope, retention, replay and status-query guarantees.

## Refund differences

Refund API replay returns the stored creation response snapshot, not a refreshed refund response; GET retrieves current status. Owned payment lookup precedes reservation/replay, preventing cross-merchant access. A payment lock serializes total refund reservation; a different key cannot reuse its existing merchant refund reference. Provider refund keys are refund-level, and refunds stay with the original charging provider. Internal compensation uses a stable compensation ID and creates a refund, not a DB rollback of the charge.

These layers prevent different duplicates. Neither Kafka producer idempotence nor a local event dedup row alone establishes exactly-once external effects. See [source services](../src/main/java/com/payflow/payment/service/) and [refund stores](../src/main/java/com/payflow/payment/refund/).
