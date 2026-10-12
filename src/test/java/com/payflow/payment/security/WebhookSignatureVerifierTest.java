package com.payflow.payment.security;

import static org.assertj.core.api.Assertions.*;

import com.payflow.payment.config.WebhookProperties;
import com.payflow.payment.exception.WebhookRequestException;
import com.payflow.payment.provider.PaymentProviderType;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class WebhookSignatureVerifierTest {
    @Test
    void standardHmacSha256Vector() {
        WebhookProperties properties = new WebhookProperties();
        properties.setASecret("Jefe");
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(properties);
        byte[] raw = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.sign(PaymentProviderType.PROVIDER_A, raw))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        verifier.verify(
                PaymentProviderType.PROVIDER_A,
                raw,
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    void absentSecretFailsClosed() {
        var verifier = new WebhookSignatureVerifier(new WebhookProperties());
        assertThatThrownBy(
                        () ->
                                verifier.verify(
                                        PaymentProviderType.PROVIDER_A,
                                        new byte[0],
                                        "00".repeat(32)))
                .isInstanceOfSatisfying(
                        WebhookRequestException.class,
                        failure -> assertThat(failure.status()).isEqualTo(503));
        assertThatThrownBy(() -> verifier.sign(PaymentProviderType.PROVIDER_A, new byte[0]))
                .isInstanceOf(IllegalStateException.class);
    }
}
