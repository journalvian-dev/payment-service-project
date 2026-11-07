package com.vian.payments.payment.port.out;

public interface PartnerClient {

    PartnerPaymentResult charge(PartnerChargeCommand command);

    PartnerPaymentResult queryStatus(String reference);
}
