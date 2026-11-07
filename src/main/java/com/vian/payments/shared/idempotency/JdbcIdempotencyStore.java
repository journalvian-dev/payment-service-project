package com.vian.payments.shared.idempotency;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

/**
 * Implementasi idempotency store dengan UNIQUE constraint di database.
 *
 * Kenapa DB, bukan Redis saja?
 * - butuh jaminan atomik lintas node (unique constraint)
 * - response asli tetap tersedia walau Redis restart / TTL habis
 * Redis cocok sebagai cache di depannya, bukan source of truth.
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String INSERT = """
            INSERT INTO idempotency_records
                (idempotency_key, request_hash, status, response_body, created_at, updated_at)
            VALUES (?, ?, 'IN_PROGRESS', NULL, ?, ?)
            """;

    private static final String SELECT = """
            SELECT request_hash, status, response_body
            FROM idempotency_records
            WHERE idempotency_key = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public IdempotencyDecision begin(String key, String requestHash) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            jdbcTemplate.update(INSERT, key, requestHash, now, now);
            return new IdempotencyDecision.Proceed();
        } catch (DuplicateKeyException duplicate) {
            // Insert kalah balapan -> baca state yang sudah ada.
            // Kalau row ternyata sudah dihapus (release), anggap Proceed.
            ResultSetExtractor<IdempotencyDecision> extractor = rs -> mapExisting(rs, requestHash);
            return jdbcTemplate.query(SELECT, extractor, key);
        }
    }

    private IdempotencyDecision mapExisting(ResultSet rs, String requestHash) throws SQLException {
        if (!rs.next()) {
            return new IdempotencyDecision.Proceed();
        }
        String storedHash = rs.getString("request_hash");
        if (!storedHash.equals(requestHash)) {
            return new IdempotencyDecision.Conflict(
                    "Idempotency-Key sudah dipakai untuk request body yang berbeda");
        }
        String status = rs.getString("status");
        if ("COMPLETED".equals(status)) {
            return new IdempotencyDecision.Replay(rs.getString("response_body"));
        }
        return new IdempotencyDecision.InProgress();
    }

    @Override
    public void complete(String key, String responseBody) {
        int updated = jdbcTemplate.update("""
                UPDATE idempotency_records
                SET status = 'COMPLETED', response_body = ?, updated_at = ?
                WHERE idempotency_key = ?
                """,
                responseBody,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC),
                key);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency record tidak ditemukan saat complete: " + key);
        }
    }

    @Override
    public void release(String key) {
        jdbcTemplate.update("""
                DELETE FROM idempotency_records
                WHERE idempotency_key = ? AND status = 'IN_PROGRESS'
                """, key);
    }

    @Override
    public int purgeOlderThan(Instant cutoff) {
        return jdbcTemplate.update("""
                DELETE FROM idempotency_records WHERE created_at < ?
                """, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
    }
}
