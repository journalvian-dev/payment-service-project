-- Portable untuk PostgreSQL dan H2 (MODE=PostgreSQL)
CREATE TABLE payments (
    id                UUID PRIMARY KEY,
    reference         VARCHAR(64)  NOT NULL,
    customer_id       VARCHAR(64)  NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    description       VARCHAR(255),
    status            VARCHAR(32)  NOT NULL,
    partner_reference VARCHAR(64),
    failure_reason    VARCHAR(255),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uk_payments_reference ON payments (reference);
CREATE INDEX idx_payments_status_updated_at ON payments (status, updated_at);

-- Idempotency-Key store: unique constraint adalah kunci anti double-charge.
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    response_body   TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_idempotency_created_at ON idempotency_records (created_at);

-- Deduplikasi webhook berdasarkan event id dari partner.
CREATE TABLE webhook_events (
    event_id    VARCHAR(128) PRIMARY KEY,
    event_type  VARCHAR(64)  NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Tabel ShedLock (distributed lock untuk scheduled job).
-- lock_until/locked_at memakai TIMESTAMP tanpa timezone sesuai kontrak ShedLock.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
