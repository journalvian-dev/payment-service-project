package com.vian.payments.shared.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxStore implements OutboxStore {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcOutboxStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public void append(OutboxMessage message) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO outbox_events
                    (id, aggregate_type, aggregate_id, event_type, payload, status,
                     attempts, created_at, next_attempt_at, published_at, last_error)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, NULL, NULL)
                """,
                message.id(),
                message.aggregateType(),
                message.aggregateId(),
                message.eventType(),
                message.payload(),
                now,
                now);
    }

    @Override
    public List<OutboxMessage> claimBatch(int limit) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jdbcTemplate.query("""
                SELECT id, aggregate_type, aggregate_id, event_type, payload, attempts, created_at
                FROM outbox_events
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> map(rs),
                now,
                Math.max(1, Math.min(limit, 500)));
    }

    @Override
    public void markPublished(java.util.UUID id, Instant publishedAt) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?, last_error = NULL
                WHERE id = ?
                """,
                OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC),
                id);
    }

    @Override
    public void markFailed(java.util.UUID id, String error, Instant nextAttemptAt, int maxAttempts) {
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET attempts = attempts + 1,
                    status = CASE WHEN attempts + 1 >= ? THEN 'DEAD' ELSE 'PENDING' END,
                    next_attempt_at = ?,
                    last_error = ?
                WHERE id = ?
                """,
                maxAttempts,
                OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC),
                truncate(error),
                id);
    }

    @Override
    public int purgePublishedBefore(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM outbox_events
                WHERE status = 'PUBLISHED' AND published_at < ?
                """,
                OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }

    private OutboxMessage map(ResultSet rs) throws SQLException {
        return new OutboxMessage(
                rs.getObject("id", java.util.UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
