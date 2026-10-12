package com.payflow.payment.config;

import com.payflow.payment.provider.PaymentProviderType;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class ProviderEndpoints {
    private final ProviderProperties properties;
    private volatile int localPort;

    public ProviderEndpoints(ProviderProperties properties) {
        this.properties = properties;
    }

    @EventListener
    public void serverStarted(WebServerInitializedEvent event) {
        // A separate Actuator server also emits this event; mock calls target the API server.
        if (event.getApplicationContext().getServerNamespace() == null) {
            localPort = event.getWebServer().getPort();
        }
    }

    public URI statusUri(PaymentProviderType provider, String reference) {
        return org.springframework.web.util.UriComponentsBuilder.fromUri(paymentsUri(provider))
                .pathSegment(reference)
                .build()
                .encode()
                .toUri();
    }

    public URI refundsUri(PaymentProviderType provider) {
        String payment = paymentsUri(provider).toString();
        return URI.create(payment.substring(0, payment.length() - "payments".length()) + "refunds");
    }

    public int localPort() {
        return localPort;
    }

    public URI paymentsUri(PaymentProviderType provider) {
        String base =
                provider == PaymentProviderType.PROVIDER_A
                        ? properties.getABaseUrl()
                        : properties.getBBaseUrl();
        if (base.isBlank()) {
            if (localPort == 0)
                throw new IllegalStateException("Local mock server has not started");
            base = "http://localhost:" + localPort + "/mock/providers/" + provider.mockPath();
        }
        URI uri = URI.create(base.replaceAll("/+$", "") + "/payments");
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Provider URL must be an HTTP(S) base URL without credentials/query/fragment");
        }
        return uri;
    }
}
