package com.payflow.payment.config;

import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.provider.PaymentProviderType;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "payflow.providers")
public class ProviderProperties {
    private boolean enabled = true;
    private int busyRetryAttempts = 2;
    private String aBaseUrl = "";
    private String bBaseUrl = "";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(2);
    private Duration orchestrationLease = Duration.ofSeconds(60);
    private Map<PaymentMethod, PaymentProviderType> routing = new EnumMap<>(PaymentMethod.class);
    private Map<PaymentProviderType, PaymentProviderType> fallback =
            new EnumMap<>(PaymentProviderType.class);
}
