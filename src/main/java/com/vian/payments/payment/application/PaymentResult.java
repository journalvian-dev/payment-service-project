package com.vian.payments.payment.application;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * View model yang stabil untuk API + cache + penyimpanan idempotency.
 * Sengaja bukan entity JPA dan bukan DTO web.
 */
public record PaymentResult(
        UUID id,
        String reference,
        String customerId,
        BigDecimal amount,
        String currency,
        String status,
        String partnerReference,
        String failureReason,
        Instant createdAt,
        boolean replayed
) implements Serializable {

    public PaymentResult withReplayed(boolean value) {
        return new PaymentResult(id, reference, customerId, amount, currency, status,
                partnerReference, failureReason, createdAt, value);
    }
}
