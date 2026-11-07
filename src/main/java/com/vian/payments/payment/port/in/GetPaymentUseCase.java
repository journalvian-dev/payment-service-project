package com.vian.payments.payment.port.in;

import java.util.UUID;

import com.vian.payments.payment.application.PaymentResult;

public interface GetPaymentUseCase {

    PaymentResult getById(UUID id);
}
