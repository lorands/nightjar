-- the application's own schema — pristine SQL, tracked by Liquibase via the changelog
CREATE TABLE orders
(
    id     VARCHAR(36)  PRIMARY KEY,
    status VARCHAR(20)  NOT NULL
);
