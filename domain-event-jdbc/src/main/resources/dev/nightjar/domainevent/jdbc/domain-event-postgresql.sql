-- nightjar domain-event outbox schema (PostgreSQL)
-- Transient reliability storage: rows are deleted after successful processing.

CREATE TABLE domain_event
(
    id           VARCHAR(36)  PRIMARY KEY,
    event_type   VARCHAR(250) NOT NULL,
    payload      BYTEA        NOT NULL,
    metadata     TEXT,
    sequence_key VARCHAR(250),
    status       VARCHAR(20)  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL, -- UTC
    modified_at  TIMESTAMP,             -- UTC
    suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_error   TEXT
);

CREATE INDEX idx_domain_event_pending
    ON domain_event (created_at)
    WHERE suspended = FALSE AND status <> 'PROCESSING';

-- Row-lock table backing sequenceKey serialization across instances.
CREATE TABLE domain_event_lock
(
    lock_key VARCHAR(250) PRIMARY KEY
);
