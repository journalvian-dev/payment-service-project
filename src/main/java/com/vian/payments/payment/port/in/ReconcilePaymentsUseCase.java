package com.vian.payments.payment.port.in;

public interface ReconcilePaymentsUseCase {

    /**
     * @return jumlah payment yang berhasil di-resolve
     */
    int reconcile();
}
