package com.vian.payments.shared.idempotency;

public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String key) {
        super("Request dengan Idempotency-Key " + key + " masih diproses. Coba lagi setelah selesai.");
    }
}
