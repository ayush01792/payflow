package com.payflow.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class MerchantCredentialService {
    private static final Logger log = LoggerFactory.getLogger(MerchantCredentialService.class);
    private final JdbcTemplate jdbc;
    private final ApiKeyHasher hasher;
    private final SecureRandom random = new SecureRandom();

    public MerchantCredentialService(JdbcTemplate jdbc, ApiKeyHasher hasher) {
        this.jdbc = jdbc;
        this.hasher = hasher;
    }

    @Transactional
    public UUID createMerchant(String externalId, String name) {
        text(externalId);
        text(name);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO merchants(id,external_merchant_id,name,status) VALUES"
                    + " (?,?,?,'ACTIVE')",
                id,
                externalId,
                name);
        return id;
    }

    @Transactional
    public IssuedKey createKey(UUID merchant, String name, Instant expiresAt) {
        text(name);
        if (expiresAt != null && !expiresAt.isAfter(Instant.now()))
            throw new IllegalArgumentException("Expiry must be in the future");
        var status =
                jdbc.queryForList(
                        "SELECT status FROM merchants WHERE id=? FOR UPDATE",
                        String.class,
                        merchant);
        if (status.isEmpty() || !status.getFirst().equals("ACTIVE"))
            throw new IllegalArgumentException("Merchant unavailable");
        String prefix = encoded(16), secret = encoded(32);
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                    + " merchant_api_keys(id,merchant_id,key_prefix,secret_hash,name,status,expires_at)"
                    + " VALUES (?,?,?,?,?,'ACTIVE',?)",
                id,
                merchant,
                prefix,
                hasher.hash(prefix, secret),
                name,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt));
        log.info("API key created credentialId={} merchantId={}", id, merchant);
        return new IssuedKey(id, prefix, "payflow_test_" + prefix + "_" + secret);
    }

    @Transactional
    public void revoke(UUID id) {
        var rows =
                jdbc.queryForList(
                        "UPDATE merchant_api_keys SET"
                            + " status='REVOKED',revoked_at=coalesce(revoked_at,now()) WHERE id=?"
                            + " RETURNING merchant_id",
                        UUID.class,
                        id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Credential unavailable");
        log.info("API key revoked credentialId={} merchantId={}", id, rows.getFirst());
    }

    @Transactional
    public void setStatus(UUID id, com.payflow.payment.entity.MerchantStatus status) {
        if (jdbc.update(
                        "UPDATE merchants SET status=?,updated_at=now() WHERE id=?",
                        status.name(),
                        id)
                != 1) throw new IllegalArgumentException("Merchant unavailable");
        log.info("Merchant status changed merchantId={} status={}", id, status);
    }

    public List<KeyMetadata> listKeys(UUID merchant) {
        return jdbc.query(
                "SELECT id,key_prefix,name,status,created_at,expires_at,last_used_at,revoked_at"
                    + " FROM merchant_api_keys WHERE merchant_id=? ORDER BY created_at,id",
                (rs, n) ->
                        new KeyMetadata(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                instant(rs, 5),
                                instant(rs, 6),
                                instant(rs, 7),
                                instant(rs, 8)),
                merchant);
    }

    private static Instant instant(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private String encoded(int count) {
        byte[] bytes = new byte[count];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 100)
            throw new IllegalArgumentException(
                    "Identifier/name must be nonblank and at most 100 characters");
    }

    public record KeyMetadata(
            UUID id,
            String prefix,
            String name,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant revokedAt) {}

    // Deliberate one-time secret return; accidental logging cannot reveal it.
    public record IssuedKey(UUID id, String prefix, String apiKey) {
        @Override
        public String toString() {
            return "IssuedKey[id=" + id + ", prefix=" + prefix + ", apiKey=REDACTED]";
        }
    }
}
