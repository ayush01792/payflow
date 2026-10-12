package com.payflow.payment.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> paymentNotFound(
            PaymentNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> rateLimitExceeded(
            RateLimitExceededException exception, HttpServletRequest request) {
        ApiError body =
                new ApiError(
                        Instant.now(),
                        429,
                        HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                        exception.getMessage(),
                        request.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(RateLimitUnavailableException.class)
    public ResponseEntity<ApiError> rateLimitUnavailable(
            RateLimitUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> idempotencyConflict(
            IdempotencyConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ResponseEntity<ApiError> invalidPaymentRequest(
            InvalidPaymentRequestException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(field -> field.getField() + ": " + field.getDefaultMessage())
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining("; "));
        return handleExceptionInternal(exception, message, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return handleExceptionInternal(
                exception,
                "Invalid JSON request: check field names, types, and paymentMethod",
                headers,
                status,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        String message = body instanceof String safeMessage ? safeMessage : "Invalid request";
        HttpStatus httpStatus = HttpStatus.valueOf(status.value());
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        ApiError apiError =
                new ApiError(
                        Instant.now(), status.value(), httpStatus.getReasonPhrase(), message, path);
        return super.handleExceptionInternal(exception, apiError, headers, status, request);
    }

    @ExceptionHandler(WebhookRequestException.class)
    public ResponseEntity<ApiError> webhookError(
            WebhookRequestException exception, HttpServletRequest request) {
        return error(HttpStatus.valueOf(exception.status()), exception.getMessage(), request);
    }

    @ExceptionHandler(RefundConflictException.class)
    public ResponseEntity<ApiError> refundConflict(
            RefundConflictException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    @ExceptionHandler(RefundNotFoundException.class)
    public ResponseEntity<ApiError> refundNotFound(
            RefundNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(InvalidRefundRequestException.class)
    public ResponseEntity<ApiError> invalidRefund(
            InvalidRefundRequestException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedError(
            Exception exception, HttpServletRequest request) {
        log.error("Unexpected failure handling {}", request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ApiError> error(
            HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                Instant.now(),
                                status.value(),
                                status.getReasonPhrase(),
                                message,
                                request.getRequestURI()));
    }
}
