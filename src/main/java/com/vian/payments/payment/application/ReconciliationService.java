package com.vian.payments.payment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.vian.payments.config.AppProperties;
import com.vian.payments.payment.domain.Payment;
import com.vian.payments.payment.port.in.ReconcilePaymentsUseCase;
import com.vian.payments.payment.port.out.PartnerClient;
import com.vian.payments.payment.port.out.PartnerPaymentResult;
import com.vian.payments.payment.port.out.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reconciliation worker: menyelesaikan transaksi IN_DOUBT.
 *
 * In-doubt = kita timeout / koneksi putus saat memanggil partner, jadi kita
 * tidak tahu apakah transaksi sudah tercatat di partner. Solusinya BUKAN retry
 * charge buta, tapi query status berdasarkan reference kita (idempotency key
 * yang kita kirim ke partner).
 */
@Service
public class ReconciliationService implements ReconcilePaymentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int BATCH_SIZE = 50;

    private final PaymentRepository payments;
    private final PaymentStateWriter paymentStateWriter;
    private final PartnerClient partnerClient;
    private final AppProperties properties;
    private final Clock clock;

    public ReconciliationService(PaymentRepository payments,
                                 PaymentStateWriter paymentStateWriter,
                                 PartnerClient partnerClient,
                                 AppProperties properties,
                                 Clock clock) {
        this.payments = payments;
        this.paymentStateWriter = paymentStateWriter;
        this.partnerClient = partnerClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public int reconcile() {
        Instant cutoff = clock.instant().minus(properties.reconciliation().inDoubtAfter());
        List<Payment> candidates = payments.findStaleInDoubt(cutoff, BATCH_SIZE);

        int resolved = 0;
        for (Payment payment : candidates) {
            try {
                PartnerPaymentResult result = partnerClient.queryStatus(payment.reference());
                if (result.status() == PartnerPaymentResult.Status.UNKNOWN) {
                    log.info("Reconciliation: {} masih UNKNOWN", payment.reference());
                    continue;
                }

                boolean changed = result.status() == PartnerPaymentResult.Status.SUCCEEDED
                        ? payment.markSucceeded(result.partnerReference())
                        : payment.markFailed(result.reason());

                if (changed) {
                    paymentStateWriter.saveAndPublish(payment);
                    resolved++;
                }
            } catch (RuntimeException e) {
                // Satu payment gagal tidak boleh menggagalkan seluruh batch.
                log.warn("Reconciliation gagal untuk {}", payment.reference(), e);
            }
        }

        if (!candidates.isEmpty()) {
            log.info("Reconciliation selesai: {} kandidat, {} resolved", candidates.size(), resolved);
        }
        return resolved;
    }
}
