-- Inbox/dedupe table untuk Kafka consumer.
-- event_id adalah primary key, jadi event yang dikirim ulang (at-least-once)
-- tidak diproses dua kali.
CREATE TABLE payment_event_log (
    event_id       UUID PRIMARY KEY,
    event_type     VARCHAR(64)  NOT NULL,
    aggregate_type VARCHAR(64)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    payload        TEXT         NOT NULL,
    consumed_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payment_event_log_aggregate ON payment_event_log (aggregate_id);
CREATE INDEX idx_payment_event_log_consumed_at ON payment_event_log (consumed_at);
