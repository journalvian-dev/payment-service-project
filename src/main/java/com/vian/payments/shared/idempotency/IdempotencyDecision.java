package com.vian.payments.shared.idempotency;

/**
 * Hasil "begin" untuk sebuah Idempotency-Key.
 */
public sealed interface IdempotencyDecision {

    /** Key baru, silakan proses request. */
    record Proceed() implements IdempotencyDecision {
    }

    /** Request pertama sudah selesai; balikan response yang tersimpan. */
    record Replay(String responseBody) implements IdempotencyDecision {
    }

    /** Request dengan key sama masih diproses request lain. */
    record InProgress() implements IdempotencyDecision {
    }

    /** Key sama tapi body request berbeda -> client bug, jangan dieksekusi. */
    record Conflict(String message) implements IdempotencyDecision {
    }
}
