package com.payflow.payment.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payflow.payment.security.MerchantCredentialService;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * Legacy workflow fixtures now authenticate through the production filter. Explicit security tests
 * do not import this helper and select credentials themselves.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MerchantTestConfiguration {
    @Bean
    static BeanPostProcessor authenticatedWorkflowFixtures(
            org.springframework.beans.factory.ObjectProvider<MerchantCredentialService> credentials,
            org.springframework.beans.factory.ObjectProvider<JdbcTemplate> database,
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> mapper) {
        return new BeanPostProcessor() {
            private final Map<String, String> keys = new HashMap<>();

            private synchronized String key(String merchant) {
                return keys.computeIfAbsent(
                        merchant,
                        m -> {
                            var jdbc = database.getObject();
                            var service = credentials.getObject();
                            var ids =
                                    jdbc.queryForList(
                                            "SELECT id FROM merchants WHERE external_merchant_id=?",
                                            UUID.class,
                                            m);
                            UUID id =
                                    ids.isEmpty()
                                            ? service.createMerchant(m, "Workflow test merchant")
                                            : ids.getFirst();
                            service.setStatus(id, com.payflow.payment.entity.MerchantStatus.ACTIVE);
                            return service.createKey(id, "Workflow integration test", null)
                                    .apiKey();
                        });
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof TestRestTemplate client) {
                    client.getRestTemplate()
                            .getInterceptors()
                            .add(
                                    (request, body, execution) -> {
                                        String path = request.getURI().getPath();
                                        if (!(path.equals("/api/v1/payments")
                                                        || path.startsWith("/api/v1/payments/")
                                                        || path.startsWith("/api/v1/refunds/"))
                                                || request.getHeaders().containsKey("X-API-Key"))
                                            return execution.execute(request, body);
                                        String merchant = "MERCHANT-123";
                                        if (path.equals("/api/v1/payments")
                                                && request.getMethod()
                                                        == org.springframework.http.HttpMethod.POST
                                                && body.length > 0) {
                                            var node = mapper.getObject().readTree(body);
                                            if (node instanceof ObjectNode object
                                                    && object.has("merchantId")) {
                                                merchant = object.get("merchantId").asText();
                                                object.remove("merchantId");
                                                body = mapper.getObject().writeValueAsBytes(object);
                                                request.getHeaders().setContentLength(body.length);
                                            }
                                        } else {
                                            String[] segments = path.split("/");
                                            if (segments.length >= 5) {
                                                try {
                                                    UUID id = UUID.fromString(segments[4]);
                                                    String sql =
                                                            path.startsWith("/api/v1/refunds/")
                                                                    ? "SELECT p.merchant_id FROM"
                                                                          + " refunds r JOIN"
                                                                          + " payments p ON"
                                                                          + " p.id=r.payment_id"
                                                                          + " WHERE r.id=?"
                                                                    : "SELECT merchant_id FROM"
                                                                          + " payments WHERE id=?";
                                                    var owners =
                                                            database.getObject()
                                                                    .queryForList(
                                                                            sql, String.class, id);
                                                    if (!owners.isEmpty())
                                                        merchant = owners.getFirst();
                                                } catch (IllegalArgumentException ignored) {
                                                }
                                            }
                                        }
                                        request.getHeaders().set("X-API-Key", key(merchant));
                                        return execution.execute(request, body);
                                    });
                }
                return bean;
            }
        };
    }
}
