package io.ekbatan.distributedjobs;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.jdbc.DefaultJdbcCustomization;
import com.github.kagkarlsson.scheduler.jdbc.JdbcCustomization;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * That {@link JobRegistry} actually pins the dialect, rather than merely being able to.
 *
 * The defect was never "we cannot work out the dialect" - it was that nobody told db-scheduler, so
 * it asked a connection that may not exist yet and kept a generic answer forever. These tests
 * therefore go through {@code build()} rather than the mapping in isolation: the mapping being
 * right proves nothing if it is not wired in.
 *
 * <p>Every URL below points at a host that does not resolve, which is the whole point: the pool is
 * built with {@code initializationFailTimeout = -1}, so construction succeeds with no database
 * anywhere, and the customization still has to come out right.
 */
class JobRegistryDialectPinningTest {

    /**
     * Why any of this matters. Every dialect override emits {@code LIMIT n}; db-scheduler's generic
     * fallback emits ANSI {@code OFFSET ... FETCH FIRST}, which MySQL and MariaDB cannot parse - so
     * a scheduler that fell back would issue an invalid poll query for its whole lifetime. If a
     * db-scheduler upgrade ever changes that, this fails and the reasoning above needs revisiting.
     */
    @Test
    void the_generic_fallback_emits_sql_mysql_and_mariadb_cannot_parse() {
        var generic = new DefaultJdbcCustomization(false).getQueryLimitPart(10);

        assertThat(generic).contains("FETCH FIRST").doesNotContain("LIMIT");
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:postgresql://nowhere-9e3f:5432/db, PostgreSQL",
        "jdbc:mariadb://nowhere-9e3f:3306/db,    MariaDB",
        "jdbc:mysql://nowhere-9e3f:3306/db,      MySQL => v8",
    })
    void the_customization_is_set_before_the_scheduler_is_created(String url, String expectedName) {
        var captured = buildAndCapture(url.trim());

        assertThat(captured)
                .as("db-scheduler was left to detect the dialect from a connection")
                .isNotNull();
        assertThat(captured.getName()).isEqualTo(expectedName.trim());
    }

    /**
     * The failure this prevents: without a pinned customization the poll query would be ANSI
     * {@code FETCH FIRST}, which MySQL and MariaDB reject outright.
     */
    @ParameterizedTest
    @CsvSource({"jdbc:mysql://nowhere-9e3f:3306/db", "jdbc:mariadb://nowhere-9e3f:3306/db"})
    void the_poll_query_uses_limit_on_mysql_and_mariadb(String url) {
        assertThat(buildAndCapture(url).getQueryLimitPart(10)).isEqualTo(" LIMIT 10");
    }

    // The "unrecognised URL leaves detection to db-scheduler" case is covered in
    // SchedulerDialectTest, not here: DataSourceConfig validates the URL when the pool is built and
    // rejects anything outside PostgreSQL/MySQL/MariaDB, so that branch is unreachable through a
    // ConnectionProvider. It stays in SchedulerDialect as a fallback for any other construction path.

    /**
     * Reads what {@code build()} put on the builder. {@code SchedulerBuilder.jdbcCustomization} is
     * protected and there is no getter, so the value is read reflectively - the alternative is
     * asserting nothing about whether the fix is actually connected.
     */
    private static JdbcCustomization buildAndCapture(String jdbcUrl) {
        var captured = new AtomicReference<JdbcCustomization>();
        JobRegistry.jobRegistry()
                .connectionProvider(hikariConnectionProvider(dataSourceConfig()
                        .jdbcUrl(jdbcUrl)
                        .username("u")
                        .password("p")
                        .maximumPoolSize(1)
                        .build()))
                .withJob(new NoOpJob())
                .registerShutdownHook(false)
                .customizeScheduler(builder -> captured.set(readCustomization(builder)))
                .build();
        return captured.get();
    }

    private static JdbcCustomization readCustomization(SchedulerBuilder builder) {
        try {
            var field = SchedulerBuilder.class.getDeclaredField("jdbcCustomization");
            field.setAccessible(true);
            return (JdbcCustomization) field.get(builder);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "SchedulerBuilder.jdbcCustomization is no longer readable; db-scheduler's shape changed", e);
        }
    }

    private static final class NoOpJob extends DistributedJob {
        @Override
        public String name() {
            return "no-op";
        }

        @Override
        public Schedule schedule() {
            return FixedDelay.ofSeconds(60);
        }

        @Override
        public void execute(ExecutionContext ctx) {
            // nothing; this job exists only so build() has something to register
        }
    }
}
