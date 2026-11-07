-- Transactional outbox: event ditulis di transaksi yang sama dengan perubahan payment.
-- Relay worker membaca PENDING lalu publish ke broker/HTTP secara terkontrol.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(64)  NOT NULL,
    aggregate_id    VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at    TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(500)
);

CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id);
