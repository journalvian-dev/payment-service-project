package com.vian.payments.shared.webhook;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWebhookEventStore implements WebhookEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcWebhookEventStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public boolean register(String eventId, String eventType) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO webhook_events (event_id, event_type, received_at)
                    VALUES (?, ?, ?)
                    """,
                    eventId,
                    eventType,
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            return true;
        } catch (DuplicateKeyException duplicate) {
            // Webhook partner bisa dikirim ulang. Primary key = idempotency.
            return false;
        }
    }
}
