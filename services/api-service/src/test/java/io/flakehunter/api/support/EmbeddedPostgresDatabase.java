package io.flakehunter.api.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * One real PostgreSQL server per test JVM, started from embedded binaries.
 *
 * <p>Why not H2? H2 only imitates Postgres, and this service relies on Postgres-specific SQL
 * ({@code ON CONFLICT}, {@code FOR UPDATE SKIP LOCKED}, partial indexes). Why not Testcontainers?
 * It needs Docker; this runs on any laptop or CI runner. The full Docker stack is still exercised
 * by the system tests in CI.
 */
public final class EmbeddedPostgresDatabase {

    private static final EmbeddedPostgres POSTGRES = start();

    private EmbeddedPostgresDatabase() {
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (IOException ignored) {
                    // JVM is exiting anyway
                }
            }));
            return pg;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl("postgres", "postgres");
    }
}
