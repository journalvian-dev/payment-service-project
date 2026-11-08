package com.vian.payments.shared.kafka;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Inbox store: dedupe event Kafka berdasarkan event_id.
 *
 * Kafka menjamin at-least-once, bukan exactly-once. Jadi event yang sama bisa
 * datang lebih dari sekali. Primary key event_id membuat pemrosesan kedua
 * menjadi no-op.
 */
@Repository
public class PaymentEventLogStore {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventLogStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PaymentEventLogStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * @return true kalau event baru diproses, false kalau duplikat.
     */
    public boolean record(PaymentEventEnvelope event) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO payment_event_log
                        (event_id, event_type, aggregate_type, aggregate_id, payload, consumed_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    event.eventId(),
                    event.eventType(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.payload() == null ? "{}" : event.payload().toString(),
                    OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            log.info("Kafka event diproses: eventId={}, type={}, aggregateId={}",
                    event.eventId(), event.eventType(), event.aggregateId());
            return true;
        } catch (DuplicateKeyException e) {
            log.info("Kafka event duplikat diabaikan: eventId={}", event.eventId());
            return false;
        }
    }
}
