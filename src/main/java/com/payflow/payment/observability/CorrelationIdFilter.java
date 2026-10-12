package com.payflow.payment.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";

    public static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = java.util.Collections.list(request.getHeaders(HEADER));
        String supplied = headers.size() == 1 ? headers.getFirst() : null;
        String id = valid(supplied) ? supplied : UUID.randomUUID().toString();
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) MDC.remove("correlationId");
            else MDC.put("correlationId", previous);
        }
    }
}
