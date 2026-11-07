package com.vian.payments.payment.application;

import java.math.BigDecimal;

public record CreatePaymentCommand(
        String customerId,
        BigDecimal amount,
        String currency,
        String description
) {
}
