package com.payflow.payment.observability;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

class CorrelationIdFilterTest {
    @AfterEach
    void clear() {
        MDC.clear();
    }

    String request(String input, boolean duplicate, boolean fail) throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        if (input != null) request.addHeader(CorrelationIdFilter.HEADER, input);
        if (duplicate) request.addHeader(CorrelationIdFilter.HEADER, "second");
        var filter = new CorrelationIdFilter();
        try {
            filter.doFilter(
                    request,
                    response,
                    (a, b) -> {
                        assertThat(MDC.get("correlationId"))
                                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
                        if (fail) throw new jakarta.servlet.ServletException("test");
                    });
        } catch (jakarta.servlet.ServletException e) {
            if (!fail) throw e;
        }
        assertThat(MDC.get("correlationId")).isNull();
        return response.getHeader(CorrelationIdFilter.HEADER);
    }

    @Test
    void preservesSafeClientId() throws Exception {
        assertThat(request("client-123._", false, false)).isEqualTo("client-123._");
    }

    @Test
    void generatesMissingIdAndDoesNotLeakAcrossRequests() throws Exception {
        assertThat(request(null, false, false)).isNotEqualTo(request(null, false, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad\nvalue", "bad value", "bad/value"})
    void replacesInvalidInput(String input) throws Exception {
        assertThat(request(input, false, false)).matches("[0-9a-f-]{36}");
    }

    @Test
    void replacesOversizedAndDuplicateInput() throws Exception {
        assertThat(request("x".repeat(1000000), false, false)).matches("[0-9a-f-]{36}");
        assertThat(request("first", true, false)).isNotEqualTo("first");
    }

    @Test
    void cleansUpEvenWhenDownstreamFails() throws Exception {
        request("safe", false, true);
    }
}
