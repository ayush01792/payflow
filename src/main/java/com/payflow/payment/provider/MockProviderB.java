package com.payflow.payment.provider;

import com.payflow.payment.config.ProviderEndpoints;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class MockProviderB implements PaymentProvider {
    private final RestClient client;
    private final ProviderEndpoints endpoints;

    public MockProviderB(
            @Qualifier("providerBRestClient") RestClient client, ProviderEndpoints endpoints) {
        this.client = client;
        this.endpoints = endpoints;
    }

    public PaymentProviderType type() {
        return PaymentProviderType.PROVIDER_B;
    }

    public ProviderRefundResult refundPayment(ProviderRefundRequest request) {
        return ProviderRefundHttpClient.refund(client, endpoints, type(), request);
    }

    public ProviderRefundStatusResult getRefundStatus(String reference) {
        return ProviderRefundHttpClient.status(client, endpoints, type(), reference);
    }

    public ProviderStatusResult getPaymentStatus(String reference) {
        try {
            ProviderStatusResult result =
                    client.get()
                            .uri(endpoints.statusUri(type(), reference))
                            .retrieve()
                            .onStatus(
                                    status -> status.isError(),
                                    (sent, response) -> ProviderHttpResponse.checkStatus(response))
                            .body(ProviderStatusResult.class);
            if (result == null
                    || result.provider() != type()
                    || !reference.equals(result.providerReference())
                    || result.status() == null
                    || ((result.status() == ProviderStatusResult.Status.SUCCESS
                                    || result.status() == ProviderStatusResult.Status.FAILED)
                            && (result.providerTransactionId() == null
                                    || result.providerTransactionId().isBlank()
                                    || result.providerTransactionId().length() > 100
                                    || result.amount() == null
                                    || result.currency() == null)))
                throw new ProviderTechnicalException("PROVIDER_INVALID_STATUS", true, false);
            return result;
        } catch (ResourceAccessException failure) {
            throw ProviderHttpResponse.transportFailure(failure);
        } catch (RestClientException failure) {
            if (ProviderHttpResponse.hasNetworkCause(failure))
                throw ProviderHttpResponse.transportFailure(failure);
            throw new ProviderTechnicalException("PROVIDER_INVALID_STATUS", true, false);
        }
    }

    public ProviderPaymentResult processPayment(ProviderPaymentRequest request) {
        try {
            ProviderPaymentResult result =
                    client.post()
                            .uri(endpoints.paymentsUri(type()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", request.idempotencyKey(type()))
                            .body(request)
                            .retrieve()
                            .onStatus(
                                    status -> status.isError(),
                                    (sent, response) -> ProviderHttpResponse.checkStatus(response))
                            .body(ProviderPaymentResult.class);
            return ProviderHttpResponse.validate(result, type());
        } catch (ResourceAccessException failure) {
            throw ProviderHttpResponse.transportFailure(failure);
        } catch (RestClientException failure) {
            if (ProviderHttpResponse.hasNetworkCause(failure))
                throw ProviderHttpResponse.transportFailure(failure);
            throw new ProviderTechnicalException("PROVIDER_INVALID_RESPONSE", true, false);
        }
    }
}
