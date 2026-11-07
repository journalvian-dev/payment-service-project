package com.vian.payments.payment.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vian.payments.payment.domain.Payment;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByReference(String reference);

    /**
     * Kandidat reconciliation: payment IN_DOUBT yang sudah lama tidak berubah.
     */
    List<Payment> findStaleInDoubt(Instant updatedBefore, int limit);
}
