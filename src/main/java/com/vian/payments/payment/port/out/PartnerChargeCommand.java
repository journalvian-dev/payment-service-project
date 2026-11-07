package com.vian.payments.payment.port.out;

import java.math.BigDecimal;

public record PartnerChargeCommand(
        String reference,
        BigDecimal amount,
        String currency,
        String customerId
) {
}
