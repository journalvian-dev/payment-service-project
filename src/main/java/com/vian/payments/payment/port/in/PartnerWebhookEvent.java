package com.vian.payments.payment.port.in;

public record PartnerWebhookEvent(
        String eventId,
        String reference,
        String status,
        String partnerReference,
        String reason
) {
}
