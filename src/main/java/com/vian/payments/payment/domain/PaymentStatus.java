package com.vian.payments.payment.domain;

public enum PaymentStatus {
    PENDING,
    IN_DOUBT,
    SUCCEEDED,
    FAILED;

    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
