package com.payflow.payment.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class ApiKeyAuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationService.class);
    private static final Pattern FORMAT =
            Pattern.compile("payflow_test_([A-Za-z0-9_-]{22})_([A-Za-z0-9_-]{43})");
    private final JdbcTemplate jdbc;
    private final ApiKeyHasher hasher;
    private final ApiKeyUsageAudit audit;

    public ApiKeyAuthenticationService(
            JdbcTemplate jdbc, ApiKeyHasher hasher, ApiKeyUsageAudit audit) {
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.audit = audit;
    }

    public MerchantPrincipal authenticate(String raw) {
        var match = FORMAT.matcher(raw == null ? "" : raw);
        if (!match.matches()) throw denied("format");
        String prefix = match.group(1), secret = match.group(2);
        var rows =
                jdbc.query(
                        "SELECT"
                            + " k.id,k.merchant_id,m.external_merchant_id,k.secret_hash,(k.status='ACTIVE'"
                            + " AND m.status='ACTIVE' AND (k.expires_at IS NULL OR"
                            + " k.expires_at>clock_timestamp())) AS usable FROM merchant_api_keys k"
                            + " JOIN merchants m ON m.id=k.merchant_id WHERE k.key_prefix=?",
                        (rs, n) ->
                                new Credential(
                                        new MerchantPrincipal(
                                                rs.getObject(2, UUID.class),
                                                rs.getObject(1, UUID.class),
                                                rs.getString(3)),
                                        rs.getString(4),
                                        rs.getBoolean(5)),
                        prefix);
        // Unknown prefixes still execute the same cryptographic comparison.
        String stored = rows.isEmpty() ? "0".repeat(64) : rows.getFirst().hash();
        boolean valid = hasher.matches(stored, prefix, secret);
        if (rows.isEmpty() || !valid || !rows.getFirst().usable()) throw denied("invalid");
        var principal = rows.getFirst().principal();
        try {
            audit.record(principal.credentialId());
        } catch (org.springframework.dao.DataAccessException
                | org.springframework.transaction.TransactionException failure) {
            log.warn(
                    "API key usage audit unavailable credentialId={} merchantId={}",
                    principal.credentialId(),
                    principal.merchantId());
        }
        log.debug(
                "API key authenticated credentialId={} merchantId={}",
                principal.credentialId(),
                principal.merchantId());
        return principal;
    }

    private BadCredentialsException denied(String category) {
        log.debug("API key authentication rejected category={}", category);
        return new BadCredentialsException("Invalid or missing credentials");
    }

    private record Credential(MerchantPrincipal principal, String hash, boolean usable) {}
}
