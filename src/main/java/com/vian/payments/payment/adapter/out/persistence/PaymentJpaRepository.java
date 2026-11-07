package com.vian.payments.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vian.payments.payment.domain.PaymentStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByReference(String reference);

    @Query("""
            SELECT p FROM PaymentJpaEntity p
            WHERE p.status = :status AND p.updatedAt < :cutoff
            ORDER BY p.updatedAt ASC
            """)
    List<PaymentJpaEntity> findStale(
            @Param("status") PaymentStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );
}
