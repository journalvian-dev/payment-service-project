package com.vian.payments.payment.reconciliation;

import java.time.Clock;
import java.time.Duration;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import com.vian.payments.payment.port.in.ReconcilePaymentsUseCase;
import com.vian.payments.shared.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final ReconcilePaymentsUseCase reconciliation;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public ReconciliationJob(ReconcilePaymentsUseCase reconciliation,
                             IdempotencyStore idempotencyStore,
                             Clock clock) {
        this.reconciliation = reconciliation;
        this.idempotencyStore = idempotencyStore;
        this.clock = clock;
    }

    /**
     * ShedLock memastikan hanya satu node yang menjalankan job ini.
     * Tanpa lock, 3 instance aplikasi = 3x panggilan status ke partner.
     */
    @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:30000}")
    @SchedulerLock(name = "payment-reconciliation", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    @CacheEvict(cacheNames = "payment-by-id", allEntries = true)
    public void run() {
        int resolved = reconciliation.reconcile();

        int purged = idempotencyStore.purgeOlderThan(clock.instant().minus(IDEMPOTENCY_RETENTION));
        if (resolved > 0 || purged > 0) {
            log.info("Reconciliation job: resolved={}, idempotencyPurged={}", resolved, purged);
        }
    }
}
