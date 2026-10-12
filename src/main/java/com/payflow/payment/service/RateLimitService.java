package com.payflow.payment.service;

import com.payflow.payment.exception.RateLimitExceededException;
import com.payflow.payment.exception.RateLimitUnavailableException;
import com.payflow.payment.observability.PayflowMetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("redis/create-payment-rate-limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final int limit;
    private final long windowMillis;

    private final PayflowMetrics metrics;

    public RateLimitService(
            StringRedisTemplate redis,
            @Value("${payflow.rate-limit.create-limit}") int limit,
            @Value("${payflow.rate-limit.create-window-seconds}") long windowSeconds,
            PayflowMetrics metrics) {
        if (limit < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException(
                    "Payment creation rate limit and window must be positive");
        }
        this.redis = redis;
        this.limit = limit;
        this.windowMillis = Math.multiplyExact(windowSeconds, 1000);
        this.metrics = metrics;
    }

    public void checkAllowed(String merchantId) {
        Long retryAfter;
        try {
            retryAfter =
                    redis.execute(
                            SCRIPT,
                            List.of("payflow:rate:create-payment:" + merchantId),
                            Integer.toString(limit),
                            Long.toString(windowMillis));
        } catch (RedisConnectionFailureException
                | RedisSystemException
                | QueryTimeoutException exception) {
            metrics.rate("COORDINATION_FAILURE");
            log.warn(
                    "Payment creation limiter unavailable; rejecting new attempt for merchant {}",
                    merchantId);
            throw new RateLimitUnavailableException();
        }
        if (retryAfter == null) {
            throw new IllegalStateException("Rate-limit script returned no result");
        }
        if (retryAfter > 0) {
            metrics.rate("REJECTED");
            log.debug("Payment creation rate-limit rejection for merchant {}", merchantId);
            throw new RateLimitExceededException(retryAfter);
        }
        metrics.rate("ALLOWED");
    }
}
