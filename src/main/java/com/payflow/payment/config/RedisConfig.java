package com.payflow.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.payment.dto.PaymentResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, PaymentResponse> paymentRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, PaymentResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keys = new StringRedisSerializer();
        // Typed JSON uses Boot's Java-time support without native serialization or polymorphic type metadata.
        Jackson2JsonRedisSerializer<PaymentResponse> values =
                new Jackson2JsonRedisSerializer<>(objectMapper, PaymentResponse.class);
        template.setKeySerializer(keys);
        template.setHashKeySerializer(keys);
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        template.setEnableDefaultSerializer(false);
        return template;
    }
}
