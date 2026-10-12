package com.payflow.payment.mock;

import com.payflow.payment.config.*;
import com.payflow.payment.security.WebhookSignatureVerifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(name = "payflow.webhooks.mock-delivery-enabled", havingValue = "true")
public class MockWebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MockWebhookDispatcher.class);
    private final MockWebhookStore store;
    private final WebhookProperties properties;
    private final ProviderEndpoints endpoints;
    private final WebhookSignatureVerifier signatures;
    private final RestClient client;

    public MockWebhookDispatcher(
            MockWebhookStore store,
            WebhookProperties properties,
            ProviderEndpoints endpoints,
            WebhookSignatureVerifier signatures,
            @Qualifier("providerARestClient") RestClient client) {
        this.store = store;
        this.properties = properties;
        this.endpoints = endpoints;
        this.signatures = signatures;
        this.client = client;
    }

    @Scheduled(
            fixedDelayString = "${payflow.webhooks.mock-poll-interval-ms:500}",
            initialDelayString = "${payflow.webhooks.mock-poll-interval-ms:500}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deliver() {
        try {
            for (int i = 0; i < 50; i++) {
                var optional = store.claim();
                if (optional.isEmpty()) return;
                var claim = optional.get();
                int status = 0;
                try {
                    String configured = properties.url(claim.provider());
                    URI destination =
                            URI.create(
                                    configured.isBlank()
                                            ? "http://localhost:"
                                                    + endpoints.localPort()
                                                    + "/api/v1/webhooks/providers/"
                                                    + claim.provider().mockPath()
                                            : configured);
                    if (!("http".equals(destination.getScheme())
                                    || "https".equals(destination.getScheme()))
                            || destination.getHost() == null
                            || destination.getUserInfo() != null
                            || destination.getFragment() != null)
                        throw new IllegalArgumentException("Invalid mock webhook destination");
                    byte[] raw = claim.payload().getBytes(StandardCharsets.UTF_8);
                    status =
                            client.post()
                                    .uri(destination)
                                    .contentType(
                                            org.springframework.http.MediaType.APPLICATION_JSON)
                                    .header(
                                            "X-Payflow-Signature",
                                            signatures.sign(claim.provider(), raw))
                                    .body(raw)
                                    .exchange(
                                            (request, response) ->
                                                    response.getStatusCode().value());
                } catch (RuntimeException unavailable) {
                    log.warn(
                            "Mock webhook delivery unavailable provider={} eventId=mock-{}"
                                    + " attempt={}",
                            claim.provider(),
                            claim.id(),
                            claim.attempt());
                }
                store.finish(claim, status);
            }
        } catch (RuntimeException databaseFailure) {
            log.error(
                    "Mock webhook dispatch persistence failed; lease recovery is available",
                    databaseFailure);
        }
    }
}
