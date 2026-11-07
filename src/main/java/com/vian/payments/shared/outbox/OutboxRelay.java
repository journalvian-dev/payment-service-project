package com.vian.payments.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.vian.payments.config.AppProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Relay worker: baca outbox PENDING, publish, tandai PUBLISHED.
 *
 * Kenapa perlu worker terpisah?
 * - request user tidak menunggu broker
 * - kalau broker down, event tetap tersimpan (tidak hilang seperti kalau publish langsung)
 * - retry + dead-letter bisa diatur
 *
 * ShedLock menjaga hanya satu node yang menjalankan relay.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxStore outboxStore;
    private final PaymentEventPublisher publisher;
    private final AppProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxStore outboxStore,
                       PaymentEventPublisher publisher,
                       AppProperties properties,
                       Clock clock) {
        this.outboxStore = outboxStore;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${app.outbox.interval-ms:5000}",
            initialDelayString = "${app.outbox.initial-delay-ms:10000}"
    )
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M", lockAtLeastFor = "PT1S")
    public void relay() {
        List<OutboxMessage> batch = outboxStore.claimBatch(BATCH_SIZE);
        int published = 0;
        int failed = 0;

        for (OutboxMessage message : batch) {
            try {
                publisher.publish(message);
                outboxStore.markPublished(message.id(), clock.instant());
                published++;
            } catch (Exception e) {
                Duration backoff = backoff(message.attempts());
                outboxStore.markFailed(
                        message.id(),
                        e.getMessage(),
                        clock.instant().plus(backoff),
                        MAX_ATTEMPTS
                );
                failed++;
                log.warn("Gagal publish outbox {} (attempt {}), retry dalam {}: {}",
                        message.id(), message.attempts() + 1, backoff, e.toString());
            }
        }

        int purged = outboxStore.purgePublishedBefore(
                clock.instant().minus(properties.outbox().retention()));

        if (published > 0 || failed > 0 || purged > 0) {
            log.info("Outbox relay: published={}, failed={}, purged={}", published, failed, purged);
        }
    }

    private Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 8); // 1s, 2s, 4s, ... max 256s
        return Duration.ofSeconds(Math.min(seconds, 300));
    }
}
