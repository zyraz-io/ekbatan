package io.ekbatan.graalvm.flyway;

import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;

/**
 * Thin wrapper around {@code Flyway.configure().dataSource(...).locations(...).load().migrate()}
 * that installs {@link NativeImageFlywayResourceProvider} when running under GraalVM native
 * image. On the JVM the resource-provider override is skipped, so the produced {@code Flyway}
 * instance is configured identically to inline {@code Flyway.configure()...migrate()} — there
 * is no JVM behavioural difference.
 *
 * <p>Use this both in tests and in production startup code that runs Flyway migrations,
 * so the same code path works regardless of whether the binary is JIT-compiled JVM or
 * AOT-compiled native image.
 */
public final class FlywayHelper {

    private FlywayHelper() {}

    /** Migrates using the conventional default location {@code classpath:db/migration}. */
    public static void migrate(String jdbcUrl, String username, String password) {
        migrate(jdbcUrl, username, password, "classpath:db/migration");
    }

    /** Migrates using the supplied locations. */
    public static void migrate(String jdbcUrl, String username, String password, String... locations) {
        var cfg = Flyway.configure().dataSource(jdbcUrl, username, password).locations(locations);
        if (NativeImageFlywayResourceProvider.inNativeImage()) {
            cfg.resourceProvider(new NativeImageFlywayResourceProvider(
                    cfg.getLocations(), Thread.currentThread().getContextClassLoader(), StandardCharsets.UTF_8));
        }
        cfg.load().migrate();
    }
}
