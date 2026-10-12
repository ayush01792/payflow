package com.payflow.payment.reconciliation;

import com.payflow.payment.dto.ProviderWebhookRequest;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.provider.PaymentProviderType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** Shared global provider-event inbox for charge and refund reports. */
@Service
public class ProviderWebhookInbox {
    private final JdbcTemplate jdbc;

    public ProviderWebhookInbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> accept(
            PaymentProviderType provider, ProviderWebhookRequest request, String hash) {
        UUID id = UUID.randomUUID();
        int added =
                jdbc.update(
                        "INSERT INTO"
                            + " provider_webhook_events(id,provider,provider_event_id,provider_transaction_id,provider_reference,payload_hash,status)"
                            + " VALUES (?,?,?,?,?,?,'RECEIVED') ON"
                            + " CONFLICT(provider,provider_event_id) DO NOTHING",
                        id,
                        provider.name(),
                        request.eventId(),
                        request.providerTransactionId(),
                        request.providerReference(),
                        hash);
        if (added == 0) {
            String original =
                    jdbc.queryForObject(
                            "SELECT payload_hash FROM provider_webhook_events WHERE provider=? AND"
                                    + " provider_event_id=?",
                            String.class,
                            provider.name(),
                            request.eventId());
            if (!hash.equals(original))
                throw new WebhookRequestException(409, "Conflicting webhook event payload");
            return Optional.empty();
        }
        return Optional.of(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID id, UUID paymentId, UUID refundId, String result) {
        jdbc.update(
                "UPDATE provider_webhook_events SET"
                        + " payment_id=?,refund_id=?,status=?,processed_at=clock_timestamp() WHERE"
                        + " id=?",
                paymentId,
                refundId,
                result,
                id);
    }
}
