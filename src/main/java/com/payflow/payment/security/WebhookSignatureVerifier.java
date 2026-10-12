package com.payflow.payment.security;

import com.payflow.payment.config.WebhookProperties;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.provider.PaymentProviderType;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class WebhookSignatureVerifier {
    private final WebhookProperties properties;

    public WebhookSignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    public void verify(PaymentProviderType provider, byte[] raw, String signature) {
        String secret = properties.secret(provider);
        if (secret.isBlank())
            throw new WebhookRequestException(503, "Webhook verification unavailable");
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signature == null ? "" : signature);
        } catch (IllegalArgumentException invalid) {
            throw new WebhookRequestException(401, "Invalid webhook signature");
        }
        if (!MessageDigest.isEqual(hmac(secret, raw), supplied))
            throw new WebhookRequestException(401, "Invalid webhook signature");
    }

    public String sign(PaymentProviderType provider, byte[] raw) {
        if (properties.secret(provider).isBlank())
            throw new IllegalStateException("Webhook signing secret unavailable");
        return HexFormat.of().formatHex(hmac(properties.secret(provider), raw));
    }

    private byte[] hmac(String secret, byte[] raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(raw);
        } catch (java.security.GeneralSecurityException unavailable) {
            throw new IllegalStateException("HMAC unavailable", unavailable);
        }
    }
}
