package com.payflow.payment.config;

import com.payflow.payment.provider.PaymentProviderType;

import jakarta.validation.constraints.*;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("payflow.webhooks")
public class WebhookProperties {
    private String aSecret = "";
    private String bSecret = "";
    private String aUrl = "";
    private String bUrl = "";
    private boolean mockDeliveryEnabled = false;

    @Min(0)
    @Max(60000)
    private long mockDelayMs = 3000;

    @Min(1)
    @Max(10)
    private int maxAttempts = 3;

    @Min(1)
    @Max(60000)
    private long retryDelayMs = 1000;

    public String secret(PaymentProviderType provider) {
        return provider == PaymentProviderType.PROVIDER_A ? aSecret : bSecret;
    }

    public String url(PaymentProviderType provider) {
        return provider == PaymentProviderType.PROVIDER_A ? aUrl : bUrl;
    }
}
