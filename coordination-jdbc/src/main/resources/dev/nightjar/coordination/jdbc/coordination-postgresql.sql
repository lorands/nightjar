-- nightjar coordination primitives (PostgreSQL)

-- Cross-instance named mutex: a row = a held lock.
-- expires_at (not a ttl) is stored so lease takeover is one portable UPDATE.
CREATE TABLE process_lock
(
    id          VARCHAR(250) PRIMARY KEY,
    owner       VARCHAR(100),
    acquired_at TIMESTAMP NOT NULL, -- UTC
    expires_at  TIMESTAMP           -- UTC; NULL = never expires
);

-- Concurrency throttle: worker rows are held permits.
CREATE TABLE worker
(
    uuid    VARCHAR(36)  PRIMARY KEY,
    type    VARCHAR(100) NOT NULL,
    created TIMESTAMP    NOT NULL -- UTC; permits expire after the configured TTL
);

CREATE INDEX idx_worker_type ON worker (type);

CREATE TABLE worker_config
(
    type        VARCHAR(100) PRIMARY KEY,
    concurrency INT NOT NULL -- 0 = suspended
);

-- Persistent one-shot timers; (aggregate_id, job_type) is the identity.
CREATE TABLE simple_scheduler
(
    aggregate_id VARCHAR(250) NOT NULL,
    job_type     VARCHAR(250) NOT NULL,
    fire_after   TIMESTAMP    NOT NULL, -- UTC
    context      TEXT,
    PRIMARY KEY (aggregate_id, job_type)
);

CREATE INDEX idx_simple_scheduler_due ON simple_scheduler (fire_after);
