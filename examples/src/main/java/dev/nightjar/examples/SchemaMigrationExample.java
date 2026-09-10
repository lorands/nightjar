package dev.nightjar.examples;

import dev.nightjar.migrations.liquibase.LiquibaseMigrator;
import dev.nightjar.migrations.schema.MigrationManifests;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Schema migrations the nightjar way: every module (nightjar's and your own
 * application — see this module's {@code META-INF/nightjar/migrations.properties})
 * ships a manifest pointing at a Liquibase changelog that references pristine
 * {@code .sql} files. One call discovers and applies everything.
 *
 * <p>Runs on H2 here; in production the same call targets PostgreSQL from an
 * init container or deploy step (see {@code dev.nightjar.migrations.liquibase.Main}).
 * nightjar's own changesets are marked {@code dbms="postgresql"}, so on H2
 * they are skipped while the application's portable changelog executes.</p>
 */
public final class SchemaMigrationExample {

    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:mem:nightjar-schema-example;DB_CLOSE_DELAY=-1";

        System.out.println("Discovered migration manifests: " + MigrationManifests.discover());

        new LiquibaseMigrator().migrate(url, null, null);

        // the application schema from the pristine SQL file is live:
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.createStatement().execute("INSERT INTO orders (id, status) VALUES ('o-1', 'NEW')");
            var resultSet = connection.createStatement().executeQuery("SELECT COUNT(*) FROM orders");
            resultSet.next();
            System.out.println("orders table ready, rows: " + resultSet.getInt(1));
        }
    }

    private SchemaMigrationExample() {
    }
}
