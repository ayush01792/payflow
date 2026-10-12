package com.payflow.payment.provider;

import com.payflow.payment.config.ProviderEndpoints;

import org.springframework.http.MediaType;
import org.springframework.web.client.*;

/** Shared wire validation for the two refund adapters, with no database access. */
final class ProviderRefundHttpClient {
    private ProviderRefundHttpClient() {}

    static ProviderRefundResult refund(
            RestClient client,
            ProviderEndpoints endpoints,
            PaymentProviderType provider,
            ProviderRefundRequest request) {
        try {
            var result =
                    client.post()
                            .uri(endpoints.refundsUri(provider))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", request.providerRefundIdempotencyKey())
                            .body(request)
                            .retrieve()
                            .onStatus(
                                    s -> s.isError(),
                                    (sent, response) -> ProviderHttpResponse.checkStatus(response))
                            .body(ProviderRefundResult.class);
            if (result == null
                    || result.provider() != provider
                    || result.outcome() == null
                    || tooLong(result.providerRefundId())
                    || tooLong(result.failureCode())
                    || ((result.outcome() == ProviderRefundResult.Outcome.SUCCESS
                                    || result.outcome() == ProviderRefundResult.Outcome.DECLINED)
                            && (result.providerRefundId() == null
                                    || result.providerRefundId().isBlank())))
                throw new ProviderTechnicalException(
                        "PROVIDER_INVALID_REFUND_RESPONSE", true, false);
            if (result.outcome() == ProviderRefundResult.Outcome.UNKNOWN)
                throw new ProviderTechnicalException("PROVIDER_REFUND_RESULT_UNKNOWN", true, false);
            if (result.outcome() == ProviderRefundResult.Outcome.TECHNICAL_FAILURE)
                throw new RetryableProviderException("PROVIDER_UNAVAILABLE", true, false);
            return result;
        } catch (RestClientException failure) {
            throw classify(failure);
        }
    }

    static ProviderRefundStatusResult status(
            RestClient client,
            ProviderEndpoints endpoints,
            PaymentProviderType provider,
            String key) {
        try {
            var result =
                    client.get()
                            .uri(
                                    org.springframework.web.util.UriComponentsBuilder.fromUri(
                                                    endpoints.refundsUri(provider))
                                            .pathSegment(key)
                                            .build()
                                            .encode()
                                            .toUri())
                            .retrieve()
                            .onStatus(
                                    s -> s.isError(),
                                    (sent, response) -> ProviderHttpResponse.checkStatus(response))
                            .body(ProviderRefundStatusResult.class);
            if (result == null
                    || result.provider() != provider
                    || !key.equals(result.providerReference())
                    || result.status() == null
                    || tooLong(result.providerRefundId())
                    || ((result.status() == ProviderStatusResult.Status.SUCCESS
                                    || result.status() == ProviderStatusResult.Status.FAILED)
                            && (result.providerRefundId() == null
                                    || result.providerRefundId().isBlank()
                                    || result.amount() == null
                                    || result.currency() == null)))
                throw new ProviderTechnicalException("PROVIDER_INVALID_REFUND_STATUS", true, false);
            return result;
        } catch (RestClientException failure) {
            throw classify(failure);
        }
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > 100;
    }

    private static ProviderTechnicalException classify(RestClientException failure) {
        if (failure instanceof ResourceAccessException
                || ProviderHttpResponse.hasNetworkCause(failure))
            return ProviderHttpResponse.transportFailure(failure);
        return new ProviderTechnicalException("PROVIDER_INVALID_REFUND_RESPONSE", true, false);
    }
}
