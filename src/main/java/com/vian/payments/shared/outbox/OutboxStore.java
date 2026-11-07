package com.vian.payments.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxStore {

    void append(OutboxMessage message);

    /**
     * Ambil event PENDING yang sudah jatuh tempo. Di production gunakan
     * SELECT ... FOR UPDATE SKIP LOCKED + batch, atau Kafka Connect/Debezium.
     */
    List<OutboxMessage> claimBatch(int limit);

    void markPublished(UUID id, Instant publishedAt);

    void markFailed(UUID id, String error, Instant nextAttemptAt, int maxAttempts);

    int purgePublishedBefore(Instant cutoff);
}
