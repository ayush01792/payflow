package com.payflow.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class ApiKeyHasher {
    private final byte[] pepper;

    public ApiKeyHasher(@Value("${payflow.security.api-key-pepper}") String value) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException(
                    "API_KEY_PEPPER must contain at least 32 UTF-8 bytes");
        pepper = value.getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String prefix, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(
                            mac.doFinal(
                                    ("payflow_test_" + prefix + "_" + secret)
                                            .getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Credential hashing unavailable");
        }
    }

    public boolean matches(String stored, String prefix, String secret) {
        return MessageDigest.isEqual(
                HexFormat.of().parseHex(stored), HexFormat.of().parseHex(hash(prefix, secret)));
    }
}
