-- H2 (PostgreSQL mode) schema for starter tests: all nightjar tables,
-- partial indexes omitted (H2 limitation)

CREATE TABLE IF NOT EXISTS domain_event (
    id           VARCHAR(36)  PRIMARY KEY,
    event_type   VARCHAR(250) NOT NULL,
    payload      BYTEA        NOT NULL,
    metadata     TEXT,
    sequence_key VARCHAR(250),
    status       VARCHAR(20)  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMP    NOT NULL,
    modified_at  TIMESTAMP,
    suspended    BOOLEAN      NOT NULL DEFAULT FALSE,
    last_error   TEXT
);
CREATE TABLE IF NOT EXISTS domain_event_lock (lock_key VARCHAR(250) PRIMARY KEY);

CREATE TABLE IF NOT EXISTS data_migration (
    id         VARCHAR(100) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    priority   INT          NOT NULL DEFAULT 100,
    attempts   INT          NOT NULL DEFAULT 0,
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMP
);
CREATE TABLE IF NOT EXISTS sequential_data_migration (
    id        VARCHAR(100) NOT NULL,
    type      VARCHAR(100) NOT NULL,
    row_index BIGINT       NOT NULL
);
CREATE TABLE IF NOT EXISTS data_migration_lock (lock_key VARCHAR(250) PRIMARY KEY);

CREATE TABLE IF NOT EXISTS process_lock (
    id          VARCHAR(250) PRIMARY KEY,
    owner       VARCHAR(100),
    acquired_at TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP
);
CREATE TABLE IF NOT EXISTS worker (
    uuid    VARCHAR(36)  PRIMARY KEY,
    type    VARCHAR(100) NOT NULL,
    created TIMESTAMP    NOT NULL
);
CREATE TABLE IF NOT EXISTS worker_config (
    type        VARCHAR(100) PRIMARY KEY,
    concurrency INT NOT NULL
);
CREATE TABLE IF NOT EXISTS simple_scheduler (
    aggregate_id VARCHAR(250) NOT NULL,
    job_type     VARCHAR(250) NOT NULL,
    fire_after   TIMESTAMP    NOT NULL,
    context      TEXT,
    PRIMARY KEY (aggregate_id, job_type)
);
