package com.payflow.payment.event;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentEventPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final PaymentEventPublisher publisher = new PaymentEventPublisher(kafka);

    @Test void publishesStoredJsonWithoutReconstructingIt() {
        UUID paymentId = UUID.randomUUID(); String json = "{\"eventVersion\":1}";
        var result = new CompletableFuture<org.springframework.kafka.support.SendResult<String, Object>>();
        when(kafka.send(eq(PaymentEventTopics.EVENTS), eq(paymentId.toString()), any())).thenReturn(result);
        assertThat(publisher.publish(paymentId, json)).isSameAs(result);
        verify(kafka).send(eq(PaymentEventTopics.EVENTS), eq(paymentId.toString()),
                argThat(value -> java.util.Arrays.equals((byte[]) value, json.getBytes(StandardCharsets.UTF_8))));
    }
    @Test void immediateFailureIsReportedToDurableOutboxWorker() {
        when(kafka.send(anyString(), anyString(), any())).thenThrow(new org.springframework.kafka.KafkaException("test"));
        assertThatThrownBy(() -> publisher.publish(UUID.randomUUID(), "{}"))
                .isInstanceOf(org.springframework.kafka.KafkaException.class);
    }
}
