package com.vian.payments.payment.port.in;

import com.vian.payments.payment.application.CreatePaymentCommand;
import com.vian.payments.payment.application.PaymentResult;

public interface CreatePaymentUseCase {

    PaymentResult create(String idempotencyKey, CreatePaymentCommand command);
}
