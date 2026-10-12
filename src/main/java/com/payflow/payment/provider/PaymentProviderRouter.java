package com.payflow.payment.provider;

import com.payflow.payment.config.ProviderProperties;
import com.payflow.payment.entity.PaymentMethod;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentProviderRouter {
    private final ProviderProperties properties;
    private final Map<PaymentProviderType, PaymentProvider> providers =
            new EnumMap<>(PaymentProviderType.class);

    public PaymentProviderRouter(
            ProviderProperties properties, List<PaymentProvider> implementations) {
        this.properties = properties;
        for (PaymentProvider provider : implementations) {
            if (providers.put(provider.type(), provider) != null)
                throw new IllegalArgumentException("Duplicate provider implementation");
        }
        for (PaymentMethod method : PaymentMethod.values()) {
            if (!providers.containsKey(properties.getRouting().get(method)))
                throw new IllegalArgumentException("Missing provider route for " + method);
        }
        for (PaymentProviderType provider : PaymentProviderType.values()) {
            PaymentProviderType fallback = properties.getFallback().get(provider);
            if (fallback == provider || !providers.containsKey(fallback))
                throw new IllegalArgumentException("Invalid provider fallback route");
        }
    }

    public PaymentProvider primary(PaymentMethod method) {
        return provider(properties.getRouting().get(method));
    }

    public PaymentProvider fallback(PaymentProviderType primary) {
        return provider(properties.getFallback().get(primary));
    }

    public PaymentProvider provider(PaymentProviderType type) {
        return providers.get(type);
    }
}
