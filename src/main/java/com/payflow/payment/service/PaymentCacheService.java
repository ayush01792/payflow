package com.payflow.payment.service;

import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.event.PaymentStatusChanged;
import com.payflow.payment.observability.PayflowMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentCacheService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCacheService.class);
    private final RedisTemplate<String, PaymentResponse> redis;
    private final Duration ttl;

    private final PayflowMetrics metrics;

    public PaymentCacheService(
            RedisTemplate<String, PaymentResponse> redis,
            @Value("${payflow.payment-cache.ttl-seconds}") long ttlSeconds,
            PayflowMetrics metrics) {
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("Payment cache TTL must be positive");
        }
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.metrics = metrics;
    }

    public Optional<PaymentResponse> get(UUID paymentId) {
        try {
            PaymentResponse payment = redis.opsForValue().get(key(paymentId));
            if (payment != null && paymentId.equals(payment.id())) {
                metrics.cache("HIT");
                log.debug("Payment cache hit {}", paymentId);
                return Optional.of(payment);
            }
            metrics.cache("MISS");
            log.debug("Payment cache miss {}", paymentId);
        } catch (RedisConnectionFailureException
                | RedisSystemException
                | QueryTimeoutException exception) {
            metrics.cache("FALLBACK");
            log.warn(
                    "Payment cache read unavailable; falling back to PostgreSQL for {}", paymentId);
        } catch (SerializationException exception) {
            metrics.cache("FALLBACK");
            log.warn(
                    "Unreadable payment cache entry; falling back to PostgreSQL for {}", paymentId);
        }
        return Optional.empty();
    }

    public void put(PaymentResponse payment) {
        try {
            redis.opsForValue().set(key(payment.id()), payment, ttl);
        } catch (RedisConnectionFailureException
                | RedisSystemException
                | QueryTimeoutException exception) {
            metrics.cache("WRITE_FAILURE");
            log.warn(
                    "Payment cache write unavailable for {}; returning database response",
                    payment.id());
        }
    }

    public void evict(UUID paymentId) {
        try {
            redis.delete(key(paymentId));
        } catch (RedisConnectionFailureException
                | RedisSystemException
                | QueryTimeoutException exception) {
            metrics.cache("EVICTION_FAILURE");
            log.warn("Payment cache eviction unavailable for {}; entry expires by TTL", paymentId);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommittedLifecycleEvent(PaymentStatusChanged event) {
        evict(event.paymentId());
    }

    private String key(UUID paymentId) {
        return "payflow:payment:" + paymentId;
    }
}
