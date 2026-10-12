package com.payflow.payment.provider;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;

/** Focused protocol validation shared by the two mock clients. It does not perform HTTP calls. */
final class ProviderHttpResponse {
    private ProviderHttpResponse() {}

    static void checkStatus(ClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        if (status < 400) return;
        boolean notProcessed =
                "true".equalsIgnoreCase(response.getHeaders().getFirst("X-Mock-Not-Processed"));
        if (status == 400 || status == 401 || status == 403 || status == 404)
            throw new ProviderRejectedException();
        if (status == 500 || status == 502 || status == 503 || status == 504) {
            throw new RetryableProviderException(
                    "PROVIDER_UNAVAILABLE", !notProcessed, notProcessed);
        }
        throw new ProviderTechnicalException("PROVIDER_PROTOCOL_ERROR", true, false);
    }

    static ProviderPaymentResult validate(
            ProviderPaymentResult result, PaymentProviderType expected) {
        if (result == null
                || result.providerName() != expected
                || result.outcome() == null
                || (result.providerTransactionId() != null
                        && result.providerTransactionId().length() > 100)
                || (result.failureCode() != null && result.failureCode().length() > 100)
                || (result.outcome() == ProviderPaymentOutcome.SUCCESS
                        && (result.providerTransactionId() == null
                                || result.providerTransactionId().isBlank()
                                || result.providerTransactionId().length() > 100))
                || (result.outcome() == ProviderPaymentOutcome.DECLINED
                        && (result.failureCode() == null
                                || result.failureCode().isBlank()
                                || result.failureCode().length() > 100))) {
            throw new ProviderTechnicalException("PROVIDER_INVALID_RESPONSE", true, false);
        }
        if (result.outcome() == ProviderPaymentOutcome.TECHNICAL_FAILURE) {
            throw new RetryableProviderException("PROVIDER_UNAVAILABLE", true, false);
        }
        return result;
    }

    static RetryableProviderException transportFailure(RestClientException failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof java.net.UnknownHostException)
                return new RetryableProviderException("PROVIDER_CONNECTION_FAILED", false, true);
            cause = cause.getCause();
        }
        return new RetryableProviderException("PROVIDER_RESPONSE_UNKNOWN", true, false);
    }

    static boolean hasNetworkCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.SocketException
                    || cause instanceof java.net.UnknownHostException) return true;
        }
        return false;
    }
}
