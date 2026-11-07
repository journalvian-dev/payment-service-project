package com.vian.payments.payment.adapter.in.web.dto;

import com.vian.payments.payment.port.in.PartnerWebhookEvent;

public record WebhookPayload(
        String eventId,
        String reference,
        String status,
        String partnerReference,
        String reason
) {

    public PartnerWebhookEvent toEvent() {
        return new PartnerWebhookEvent(eventId, reference, status, partnerReference, reason);
    }
}
