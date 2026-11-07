package com.vian.payments.shared.webhook;

public interface WebhookEventStore {

    /**
     * @return true kalau event baru, false kalau duplikat (sudah pernah diterima)
     */
    boolean register(String eventId, String eventType);
}
