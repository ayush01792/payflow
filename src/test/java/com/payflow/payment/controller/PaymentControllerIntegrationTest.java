package com.payflow.payment.controller;

import com.payflow.payment.support.KafkaIntegrationSupport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payflow.payment.entity.PaymentStatus;
import com.payflow.payment.repository.PaymentRepository;
import com.payflow.payment.repository.PaymentIdempotencyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.TestPropertySource(properties={"payflow.security.api-key-pepper=deterministic-test-pepper-at-least-32-bytes","payflow.mock-providers.enabled=true"})
@org.springframework.context.annotation.Import(com.payflow.payment.support.MerchantTestConfiguration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIntegrationTest extends KafkaIntegrationSupport {

    private static final String PAYMENTS = "/api/v1/payments";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("payflow_api_test")
            .withUsername("payflow_test")
            .withPassword("test_only_password");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private final TestRestTemplate http;
    private final PaymentRepository repository;
    private final ObjectMapper objectMapper;
    private final PaymentIdempotencyRepository idempotency;

    @Autowired
    PaymentControllerIntegrationTest(TestRestTemplate http, PaymentRepository repository,
                                     ObjectMapper objectMapper, PaymentIdempotencyRepository idempotency) {
        this.http = http;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
    }

    @BeforeEach
    void clearPayments() {
        idempotency.deleteAll();
        repository.deleteAll();
    }

    @Test
    void createReturnsServerGeneratedFieldsAndPersistsPayment() {
        ResponseEntity<JsonNode> response = create("2499.00");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        UUID id = UUID.fromString(body.path("id").asText());
        assertThat(response.getHeaders().getLocation()).hasToString(PAYMENTS + "/" + id);
        assertThat(body.path("merchantId").asText()).isEqualTo("MERCHANT-123");
        assertThat(body.path("userId").asText()).isEqualTo("USER-456");
        assertThat(body.path("amount").decimalValue()).isEqualByComparingTo("2499.00");
        assertThat(body.path("currency").asText()).isEqualTo("INR");
        assertThat(body.path("paymentMethod").asText()).isEqualTo("UPI");
        assertThat(body.path("status").asText()).isEqualTo("CREATED");
        Instant createdAt = Instant.parse(body.path("createdAt").asText());
        assertThat(createdAt).isNotNull();
        assertThat(Instant.parse(body.path("updatedAt").asText())).isEqualTo(createdAt);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-100", "0", "0.00001", "1000000000000000"})
    void invalidAmountsAreRejected(String amount) {
        ObjectNode request = validRequest(amount);
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCXYZ", "ZZZ", "inr", ""})
    void invalidCurrencyIsRejected(String currency) {
        ObjectNode request = validRequest("100");
        request.put("currency", currency);
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"merchantId", "userId", "amount", "currency", "paymentMethod"})
    void missingRequiredFieldIsRejected(String field) {
        ObjectNode request = validRequest("100");
        request.remove(field);
        if (field.equals("merchantId")) {
            // Merchant identity is now supplied by the test credential, not request JSON.
            var response = http.postForEntity(PAYMENTS, withKey(request), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().path("merchantId").asText()).isEqualTo("MERCHANT-123");
            return;
        }
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "status", "createdAt", "updatedAt"})
    void clientCannotSupplyServerOwnedFields(String field) {
        ObjectNode request = validRequest("100");
        request.put(field, "client-supplied");
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
        assertThat(repository.count()).isZero();
    }

    @Test
    void invalidPaymentMethodIsRejected() {
        ObjectNode request = validRequest("100");
        request.put("paymentMethod", "CASH");
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
    }

    @Test
    void blankAndOversizedIdentifiersAreRejected() {
        ObjectNode request = validRequest("100");
        request.put("userId", "   ");
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
        request.put("userId", "x".repeat(101));
        assertBadRequest(http.postForEntity(PAYMENTS, withKey(request), JsonNode.class), PAYMENTS);
    }

    @Test
    void getExistingPaymentReturnsResponseDto() {
        JsonNode created = create("1250.1234").getBody();
        ResponseEntity<JsonNode> response = http.getForEntity(
                PAYMENTS + "/" + created.path("id").asText(), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void getUnknownPaymentReturnsNotFound() {
        String path = PAYMENTS + "/" + UUID.randomUUID();
        ResponseEntity<JsonNode> response = http.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().path("status").asInt()).isEqualTo(404);
        assertThat(response.getBody().path("error").asText()).isEqualTo("Not Found");
        assertThat(response.getBody().path("message").asText()).contains("Payment not found");
        assertThat(response.getBody().path("path").asText()).isEqualTo(path);
        assertThat(Instant.parse(response.getBody().path("timestamp").asText())).isNotNull();
        assertThat(response.getBody().has("trace")).isFalse();
    }

    @Test
    void malformedPaymentIdReturnsBadRequest() {
        String path = PAYMENTS + "/not-a-uuid";
        assertBadRequest(http.getForEntity(path, JsonNode.class), path);
    }

    @Test
    void listingPaginatesAndSortsByAmount() {
        create("10");
        create("30");
        create("20");
        ResponseEntity<JsonNode> response = http.getForEntity(
                PAYMENTS + "?page=0&size=2&sort=amount,desc", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(2);
        assertThat(body.path("totalElements").asLong()).isEqualTo(3);
        assertThat(body.path("totalPages").asInt()).isEqualTo(2);
        assertThat(body.path("content").size()).isEqualTo(2);
        assertThat(body.path("content").get(0).path("amount").decimalValue()).isEqualByComparingTo("30");
        assertThat(body.path("content").get(1).path("amount").decimalValue()).isEqualByComparingTo("20");
        JsonNode secondPage = http.getForObject(PAYMENTS + "?page=1&size=2&sort=amount,desc", JsonNode.class);
        assertThat(secondPage.path("page").asInt()).isEqualTo(1);
        assertThat(secondPage.path("content").size()).isEqualTo(1);
        assertThat(secondPage.path("content").get(0).path("amount").decimalValue()).isEqualByComparingTo("10");
    }

    @Test
    void listingSupportsCreationTimeSortingAndDefaults() {
        JsonNode first = create("10").getBody();
        JsonNode second = create("20").getBody();
        JsonNode ascending = http.getForObject(PAYMENTS + "?sort=createdAt,asc", JsonNode.class);
        assertThat(ascending.path("content").get(0).path("id")).isEqualTo(first.path("id"));
        assertThat(ascending.path("content").get(1).path("id")).isEqualTo(second.path("id"));
        JsonNode defaultPage = http.getForObject(PAYMENTS, JsonNode.class);
        assertThat(defaultPage.path("size").asInt()).isEqualTo(20);
        assertThat(defaultPage.path("content").get(0).path("id")).isEqualTo(second.path("id"));
    }

    @Test
    void emptyPageHasStableStructure() {
        ResponseEntity<JsonNode> response = http.getForEntity(PAYMENTS, JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body.path("content").isArray()).isTrue();
        assertThat(body.path("content").size()).isZero();
        assertThat(body.path("page").asInt()).isZero();
        assertThat(body.path("size").asInt()).isEqualTo(20);
        assertThat(body.path("totalElements").asLong()).isZero();
        assertThat(body.path("totalPages").asInt()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "size=2147483647", "page=abc",
            "sort=status,asc", "sort=amount,invalid", "sort=amount,ignorecase",
            "page=2147483647&size=100"})
    void invalidPaginationAndSortingAreRejected(String query) {
        assertBadRequest(http.getForEntity(PAYMENTS + "?" + query, JsonNode.class), PAYMENTS);
    }

    @Test
    void pageSizeAtMaximumIsAccepted() {
        ResponseEntity<JsonNode> response = http.getForEntity(PAYMENTS + "?size=100", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("size").asInt()).isEqualTo(100);
    }

    @Test
    void healthEndpointStillWorksWithDatabaseConfigured() {
        ResponseEntity<JsonNode> response = http.getForEntity("/api/v1/health", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().path("service").asText()).isEqualTo("payflow");
    }

    private ResponseEntity<JsonNode> create(String amount) {
        ResponseEntity<JsonNode> response = http.postForEntity(PAYMENTS, withKey(validRequest(amount)), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private HttpEntity<ObjectNode> withKey(ObjectNode request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        return new HttpEntity<>(request, headers);
    }

    private ObjectNode validRequest(String amount) {
        return objectMapper.createObjectNode()
                .put("merchantId", "MERCHANT-123")
                .put("userId", "USER-456")
                .put("amount", new BigDecimal(amount))
                .put("currency", "INR")
                .put("paymentMethod", "UPI");
    }

    private void assertBadRequest(ResponseEntity<JsonNode> response, String path) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("error").asText()).isEqualTo("Bad Request");
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("path").asText()).isEqualTo(path);
        assertThat(Instant.parse(body.path("timestamp").asText())).isNotNull();
        assertThat(body.has("trace")).isFalse();
    }
}
