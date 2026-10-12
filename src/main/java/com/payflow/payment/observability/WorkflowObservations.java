package com.payflow.payment.observability;

import com.payflow.payment.provider.*;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(0)
public class WorkflowObservations {
    private final ObservationRegistry observations;
    private final PayflowMetrics metrics;

    public WorkflowObservations(ObservationRegistry observations, PayflowMetrics metrics) {
        this.observations = observations;
        this.metrics = metrics;
    }

    @Around(
            "execution(* com.payflow.payment.provider.PaymentProvider+.processPayment(..)) ||"
                + " execution(* com.payflow.payment.provider.PaymentProvider+.refundPayment(..)) ||"
                + " execution(* com.payflow.payment.provider.PaymentProvider+.getPaymentStatus(..))"
                + " || execution(*"
                + " com.payflow.payment.provider.PaymentProvider+.getRefundStatus(..))")
    public Object provider(ProceedingJoinPoint call) throws Throwable {
        var provider = ((PaymentProvider) call.getTarget()).type();
        String method = call.getSignature().getName();
        var operation =
                method.equals("processPayment")
                        ? PayflowMetrics.Operation.PAYMENT
                        : method.equals("refundPayment")
                                ? PayflowMetrics.Operation.REFUND
                                : PayflowMetrics.Operation.STATUS_QUERY;
        var observation =
                Observation.createNotStarted("payflow.provider.call", observations)
                        .lowCardinalityKeyValue("provider", provider.name())
                        .lowCardinalityKeyValue("operation", operation.name())
                        .start();
        PayflowMetrics.Outcome outcome = PayflowMetrics.Outcome.TECHNICAL_FAILURE;
        try (var scope = observation.openScope()) {
            Object result = call.proceed();
            if (result instanceof ProviderPaymentResult payment)
                outcome =
                        payment.outcome() == ProviderPaymentOutcome.SUCCESS
                                ? PayflowMetrics.Outcome.SUCCESS
                                : PayflowMetrics.Outcome.DECLINED;
            else if (result instanceof ProviderRefundResult refund)
                outcome =
                        switch (refund.outcome()) {
                            case SUCCESS -> PayflowMetrics.Outcome.SUCCESS;
                            case DECLINED -> PayflowMetrics.Outcome.DECLINED;
                            case TECHNICAL_FAILURE -> PayflowMetrics.Outcome.TECHNICAL_FAILURE;
                            case UNKNOWN -> PayflowMetrics.Outcome.UNKNOWN;
                        };
            else if (result instanceof ProviderStatusResult status)
                outcome =
                        status.status() == ProviderStatusResult.Status.SUCCESS
                                ? PayflowMetrics.Outcome.SUCCESS
                                : status.status() == ProviderStatusResult.Status.FAILED
                                        ? PayflowMetrics.Outcome.DECLINED
                                        : PayflowMetrics.Outcome.UNKNOWN;
            else if (result instanceof ProviderRefundStatusResult status)
                outcome =
                        status.status() == ProviderStatusResult.Status.SUCCESS
                                ? PayflowMetrics.Outcome.SUCCESS
                                : status.status() == ProviderStatusResult.Status.FAILED
                                        ? PayflowMetrics.Outcome.DECLINED
                                        : PayflowMetrics.Outcome.UNKNOWN;
            return result;
        } catch (Throwable failure) {
            if (failure instanceof ProviderTechnicalException technical
                    && technical.failureCode().contains("TIMEOUT"))
                outcome = PayflowMetrics.Outcome.TIMEOUT;
            observation.error(failure);
            throw failure;
        } finally {
            observation.lowCardinalityKeyValue("outcome", outcome.name());
            metrics.provider(provider, operation, outcome);
            observation.stop();
        }
    }

    @Around(
            "execution(* com.payflow.payment.service.PaymentOrchestrator.handle(..)) || execution(*"
                + " com.payflow.payment.service.RefundOrchestrator.handle(..)) || execution(*"
                + " com.payflow.payment.service.PaymentReconciliationService.reconcileBatch(..)) ||"
                + " execution(*"
                + " com.payflow.payment.service.RefundReconciliationService.reconcileBatch(..)) ||"
                + " execution(* com.payflow.payment.outbox.OutboxPublisher.publishPending(..)) ||"
                + " execution(* com.payflow.payment.service.ProviderWebhookService.handle(..))")
    public Object workflow(ProceedingJoinPoint call) throws Throwable {
        String type = call.getTarget().getClass().getSimpleName();
        String name =
                type.equals("OutboxPublisher")
                        ? "payflow.outbox.publish"
                        : type.equals("ProviderWebhookService")
                                ? "payflow.webhook.processing"
                                : type.contains("Reconciliation")
                                        ? "payflow.reconciliation"
                                        : type.equals("RefundOrchestrator")
                                                ? "payflow.refund.orchestration"
                                                : "payflow.payment.orchestration";
        String domain =
                type.equals("OutboxPublisher")
                        ? "OUTBOX"
                        : type.equals("ProviderWebhookService")
                                ? "WEBHOOK"
                                : type.startsWith("Refund") ? "REFUND" : "PAYMENT";
        var observation =
                Observation.createNotStarted(name, observations)
                        .lowCardinalityKeyValue("domain", domain);
        if (type.equals("ProviderWebhookService"))
            observation.lowCardinalityKeyValue(
                    "provider", ((PaymentProviderType) call.getArgs()[0]).name());
        observation.start();
        try (var scope = observation.openScope()) {
            Object result = call.proceed();
            if (type.equals("ProviderWebhookService")) metrics.webhook(true);
            observation.lowCardinalityKeyValue("result", "SUCCESS");
            return result;
        } catch (Throwable failure) {
            if (type.equals("ProviderWebhookService")) metrics.webhook(false);
            observation.lowCardinalityKeyValue("result", "FAILED");
            observation.error(failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
