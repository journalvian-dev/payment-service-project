package com.vian.payments.shared.idempotency;

import java.time.Instant;

public interface IdempotencyStore {

    IdempotencyDecision begin(String key, String requestHash);

    void complete(String key, String responseBody);

    /**
     * Hapus key yang masih IN_PROGRESS ketika proses gagal sebelum side effect
     * ke partner terjadi, supaya client boleh retry dengan key yang sama.
     */
    void release(String key);

    int purgeOlderThan(Instant cutoff);
}
