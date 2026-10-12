package com.payflow.payment.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.payflow.payment.dto.CreatePaymentRequest;
import com.payflow.payment.dto.PageResponse;
import com.payflow.payment.dto.PaymentResponse;
import com.payflow.payment.exception.InvalidPaymentRequestException;
import com.payflow.payment.exception.PaymentNotFoundException;
import com.payflow.payment.exception.RateLimitExceededException;
import com.payflow.payment.exception.RateLimitUnavailableException;
import com.payflow.payment.repository.PaymentRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "amount");
    private final PaymentRepository repository;

    private final IdempotencyService idempotencyService;

    private final PaymentCacheService cache;
    private final RateLimitService rateLimit;

    public PaymentService(PaymentRepository repository, IdempotencyService idempotencyService,
                          PaymentCacheService cache, RateLimitService rateLimit) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.cache = cache;
        this.rateLimit = rateLimit;
    }

    public PaymentCreationResult createPayment(String merchantId, CreatePaymentRequest request, String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new InvalidPaymentRequestException("Idempotency-Key must be nonblank and at most 128 characters");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(request.currency());
        } catch (IllegalArgumentException exception) {
            throw new InvalidPaymentRequestException("Currency must be a recognized ISO 4217 code");
        }

        String hash = requestHash(merchantId, request, currency);
        var replay = idempotencyService.findReplay(merchantId, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            rateLimit.checkAllowed(merchantId);
        } catch (RateLimitExceededException | RateLimitUnavailableException exception) {
            // A concurrent request may have committed after our first lookup.
            return idempotencyService.findReplay(merchantId, key, hash)
                    .orElseThrow(() -> exception);
        }
        try {
            return idempotencyService.create(merchantId, request, key, hash, currency);
        } catch (DataIntegrityViolationException exception) {
            if (!isKeyCollision(exception)) {
                throw exception;
            }
            // The proxied create method has already rolled back; never query its failed transaction.
            return idempotencyService.replay(merchantId, key, hash);
        }
    }

    public PaymentResponse getPayment(UUID paymentId, String merchantId) {
        var cached = cache.get(paymentId);
        if (cached.isPresent()) {
            if (merchantId.equals(cached.get().merchantId())) return cached.get();
            throw new PaymentNotFoundException(paymentId);
        }
        // The repository's own read-only transaction completes before cache population.
        PaymentResponse payment = repository.findByIdAndMerchantId(paymentId, merchantId)
                .map(PaymentMapper::toResponse)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        cache.put(payment);
        return payment;
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listPayments(String merchantId, int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPaymentRequestException("Page must be nonnegative and size must be between 1 and 100");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new InvalidPaymentRequestException("Requested page exceeds the supported database offset");
        }
        for (Sort.Order order : sort) {
            if (order.isIgnoreCase()) {
                throw new InvalidPaymentRequestException("Case-insensitive sorting is not supported");
            }
            if (!SORT_FIELDS.contains(order.getProperty())) {
                throw new InvalidPaymentRequestException("Sorting is supported only on createdAt and amount");
            }
        }
        Sort requestedSort = sort.isSorted() ? sort : Sort.by(Sort.Direction.DESC, "createdAt");
        // A unique tie-breaker makes pages deterministic when amounts/timestamps match.
        Pageable pageable = PageRequest.of(page, size, requestedSort.and(Sort.by("id")));
        Page<PaymentResponse> result = repository.findAllByMerchantId(merchantId, pageable).map(PaymentMapper::toResponse);
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private String requestHash(String merchantId, CreatePaymentRequest request, Currency currency) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> fields = List.of(merchantId, request.userId(),
                    request.amount().stripTrailingZeros().toPlainString(),
                    currency.getCurrencyCode(), request.paymentMethod().name());
            // Length-prefix UTF-8 fields so embedded separators cannot create ambiguous fingerprints.
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean isKeyCollision(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && IdempotencyService.KEY_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
