-- nightjar data-migration queues (PostgreSQL)
-- Transient work queues: rows are deleted after successful processing.

CREATE TABLE data_migration
(
    id         VARCHAR(100) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    priority   INT          NOT NULL DEFAULT 100,
    attempts   INT          NOT NULL DEFAULT 0,
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMP -- UTC
);

CREATE INDEX idx_data_migration_claimable
    ON data_migration (priority, type)
    WHERE claimed_by IS NULL;

CREATE TABLE sequential_data_migration
(
    id        VARCHAR(100) NOT NULL,
    type      VARCHAR(100) NOT NULL,
    row_index BIGINT       NOT NULL
);

CREATE INDEX idx_sequential_data_migration_order
    ON sequential_data_migration (row_index);

-- Row-lock table: sequential type locks + enqueue serialization.
CREATE TABLE data_migration_lock
(
    lock_key VARCHAR(250) PRIMARY KEY
);
