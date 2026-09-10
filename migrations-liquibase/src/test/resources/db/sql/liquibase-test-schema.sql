-- pristine SQL, referenced from the changelog via sqlFile — never transformed
CREATE TABLE liquibase_smoke
(
    id   VARCHAR(36)  PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE INDEX idx_liquibase_smoke_name ON liquibase_smoke (name);
