package com.vian.payments.payment.port.in;

public interface HandlePartnerWebhookUseCase {

    void handle(PartnerWebhookEvent event);
}
