package com.payflow.payment.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.payflow.payment.entity.Payment;
import com.payflow.payment.entity.PaymentMethod;
import com.payflow.payment.entity.PaymentStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PaymentRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("payflow_test")
            .withUsername("payflow_test")
            .withPassword("test_only_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private final PaymentRepository repository;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    PaymentRepositoryIntegrationTest(PaymentRepository repository, EntityManager entityManager,
                                     JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Test
    void paymentRoundTripsThroughPostgreSql() {
        Payment payment = repository.saveAndFlush(newPayment());
        UUID id = payment.getId();
        entityManager.clear();

        Payment loaded = repository.findById(id).orElseThrow();
        assertThat(id).isNotNull();
        assertThat(loaded.getMerchantId()).isEqualTo("merchant-1");
        assertThat(loaded.getUserId()).isEqualTo("user-1");
        assertThat(loaded.getAmount()).isEqualByComparingTo("1250.1234");
        assertThat(loaded.getCurrency()).isEqualTo(Currency.getInstance("INR"));
        assertThat(loaded.getPaymentMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT currency FROM payments WHERE id = ?", String.class, id)).isEqualTo("INR");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void updateRefreshesTimestampAndPreservesCreationTime() {
        Payment payment = repository.saveAndFlush(newPayment());
        UUID id = payment.getId();
        Instant createdAt = payment.getCreatedAt();
        Instant previousUpdatedAt = payment.getUpdatedAt();
        entityManager.clear();

        Payment loaded = repository.findById(id).orElseThrow();
        loaded.setStatus(PaymentStatus.PROCESSING);
        repository.flush();
        entityManager.clear();

        Payment updated = repository.findById(id).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(previousUpdatedAt);
    }

    @Test
    void databaseRejectsNonPositiveAmountEvenWithoutBeanValidation() {
        // Direct SQL deliberately bypasses JPA validation to exercise the DB constraint.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO payments
                    (id, merchant_id, user_id, amount, currency, payment_method, status, created_at, updated_at)
                VALUES (?, 'merchant-1', 'user-1', 0, 'INR', 'UPI', 'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_payments_amount_positive");
    }

    private Payment newPayment() {
        return new Payment("merchant-1", "user-1", new BigDecimal("1250.1234"),
                Currency.getInstance("INR"), PaymentMethod.UPI, PaymentStatus.CREATED);
    }
}
