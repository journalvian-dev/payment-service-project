package com.vian.payments.payment.domain;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String message) {
        super(message);
    }

    public static PaymentNotFoundException byId(Object id) {
        return new PaymentNotFoundException("Payment tidak ditemukan: " + id);
    }
}
