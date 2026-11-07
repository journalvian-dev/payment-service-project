package com.vian.payments.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.domain.PaymentStatus;
import com.vian.payments.payment.port.out.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentPersistenceAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    public PaymentPersistenceAdapter(PaymentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = jpaRepository.findById(payment.id())
                .orElseGet(() -> new PaymentJpaEntity(payment.id()));
        entity.apply(payment);
        return jpaRepository.saveAndFlush(entity).toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return jpaRepository.findById(id).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByReference(String reference) {
        return jpaRepository.findByReference(reference).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public List<Payment> findStaleInDoubt(Instant updatedBefore, int limit) {
        return jpaRepository.findStale(PaymentStatus.IN_DOUBT, updatedBefore, PageRequest.of(0, limit))
                .stream()
                .map(PaymentJpaEntity::toDomain)
                .toList();
    }
}
