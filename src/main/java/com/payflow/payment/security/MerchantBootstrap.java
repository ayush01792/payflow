package com.payflow.payment.security;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

@Component
@Profile("bootstrap")
@ConditionalOnProperty(name = "payflow.bootstrap.action")
public class MerchantBootstrap implements CommandLineRunner {
    private final MerchantCredentialService credentials;
    private final Environment env;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public MerchantBootstrap(
            MerchantCredentialService credentials,
            Environment env,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.credentials = credentials;
        this.env = env;
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) throws Exception {
        String action = env.getRequiredProperty("payflow.bootstrap.action");
        if (action.equals("revoke")) {
            credentials.revoke(
                    UUID.fromString(env.getRequiredProperty("payflow.bootstrap.credential-id")));
            return;
        }
        if (action.equals("suspend") || action.equals("activate")) {
            credentials.setStatus(
                    UUID.fromString(env.getRequiredProperty("payflow.bootstrap.merchant-id")),
                    action.equals("activate")
                            ? com.payflow.payment.entity.MerchantStatus.ACTIVE
                            : com.payflow.payment.entity.MerchantStatus.SUSPENDED);
            return;
        }
        if (!action.equals("create-key"))
            throw new IllegalArgumentException("Unsupported bootstrap action");
        String external = env.getRequiredProperty("payflow.bootstrap.external-id");
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM merchants WHERE external_merchant_id=?",
                        UUID.class,
                        external);
        UUID id =
                ids.isEmpty()
                        ? credentials.createMerchant(
                                external,
                                env.getRequiredProperty("payflow.bootstrap.merchant-name"))
                        : ids.getFirst();
        // Prepare an owner-only temporary file before issuing a credential. No raw key is logged.
        Path output =
                Files.createTempFile(
                        "payflow-credential-",
                        ".api-key",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            String expiry = env.getProperty("payflow.bootstrap.expires-at");
            var issued =
                    credentials.createKey(
                            id,
                            env.getRequiredProperty("payflow.bootstrap.key-name"),
                            expiry == null ? null : java.time.Instant.parse(expiry));
            Files.writeString(output, issued.apiKey() + "\n");
            System.out.println(
                    "Merchant ID: "
                            + id
                            + "; credential ID: "
                            + issued.id()
                            + "; one-time key file: "
                            + output);
        } catch (Exception failure) {
            Files.deleteIfExists(output);
            throw failure;
        }
    }
}
