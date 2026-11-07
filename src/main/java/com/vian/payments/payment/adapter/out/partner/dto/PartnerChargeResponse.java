package com.vian.payments.payment.adapter.out.partner.dto;

public record PartnerChargeResponse(
        String partnerReference,
        String status,
        String reason
) {
}
