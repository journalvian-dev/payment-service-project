package com.vian.payments.payment.adapter.out.partner.dto;

import java.math.BigDecimal;

public record PartnerChargeRequest(
        String reference,
        BigDecimal amount,
        String currency,
        String customerId
) {
}
