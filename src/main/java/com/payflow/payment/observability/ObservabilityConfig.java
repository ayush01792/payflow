package com.payflow.payment.observability;

import org.slf4j.MDC;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {
    @Bean
    RestClientCustomizer safeCorrelationPropagation() {
        return builder ->
                builder.requestInterceptor(
                        (request, body, execution) -> {
                            String id = MDC.get("correlationId");
                            if (CorrelationIdFilter.valid(id))
                                request.getHeaders().set(CorrelationIdFilter.HEADER, id);
                            return execution.execute(request, body);
                        });
    }
}
