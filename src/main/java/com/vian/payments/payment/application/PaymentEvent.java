package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.vian.payments.payment.domain.Payment;

/**
 * Payload event yang dipublikasikan lewat outbox.
 * Sengaja flat dan stabil supaya consumer tidak bergantung pada entity JPA.
 */
public record PaymentEvent(
        UUID paymentId,
        String reference,
        String customerId,
        BigDecimal amount,
        String currency,
        String status,
        String partnerReference,
        Instant occurredAt
) {

    public static PaymentEvent from(Payment payment) {
        return new PaymentEvent(
                payment.id(),
                payment.reference(),
                payment.customerId(),
                payment.money().amount(),
                payment.money().currency(),
                payment.status().name(),
                payment.partnerReference(),
                payment.updatedAt()
        );
    }
}
