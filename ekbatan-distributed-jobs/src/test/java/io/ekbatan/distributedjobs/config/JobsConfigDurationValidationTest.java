package io.ekbatan.distributedjobs.config;

import static io.ekbatan.distributedjobs.config.JobsConfig.jobsConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Rejecting intervals that make db-scheduler spin.
 *
 * <p>Nothing validated these before, so a mistyped {@code PT0S} travelled from YAML through
 * {@code JobsConfig} into db-scheduler untouched. It does not fail there either: the poll loop
 * waits with {@code if (duration.toMillis() > 0) { ...sleep... }}, so zero means <em>skip the
 * wait</em> and the scheduler queries the database as fast as one core can go - silently, with the
 * application reporting healthy.
 */
class JobsConfigDurationValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT-10M"})
    void a_non_positive_polling_interval_is_rejected(String value) {
        assertThatThrownBy(() ->
                        jobsConfig().pollingInterval(Duration.parse(value)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ekbatan.jobs.polling-interval")
                .hasMessageContaining("tight loop");
    }

    /**
     * The heartbeat is not only a heartbeat: db-scheduler builds the dead-execution window from it
     * as {@code interval x 2}, so a tiny value also makes live executions look dead.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S"})
    void a_non_positive_heartbeat_interval_is_rejected(String value) {
        assertThatThrownBy(() ->
                        jobsConfig().heartbeatInterval(Duration.parse(value)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ekbatan.jobs.heartbeat-interval");
    }

    /** Zero here is a real choice - "do not wait for in-flight jobs" - so only negatives are wrong. */
    @Test
    void a_zero_shutdown_wait_is_allowed_but_a_negative_one_is_not() {
        assertThat(jobsConfig().shutdownMaxWait(Duration.ZERO).build().shutdownMaxWait)
                .contains(Duration.ZERO);

        assertThatThrownBy(() ->
                        jobsConfig().shutdownMaxWait(Duration.ofSeconds(-1)).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ekbatan.jobs.shutdown-max-wait");
    }

    @Test
    void the_error_names_the_property_and_the_offending_value() {
        assertThatThrownBy(() -> jobsConfig().pollingInterval(Duration.ZERO).build())
                .hasMessageContaining("ekbatan.jobs.polling-interval")
                .hasMessageContaining("PT0S");
    }

    @Test
    void ordinary_values_are_accepted() {
        var config = jobsConfig()
                .pollingInterval(Duration.ofSeconds(10))
                .heartbeatInterval(Duration.ofMinutes(5))
                .shutdownMaxWait(Duration.ofSeconds(30))
                .build();

        assertThat(config.pollingInterval).contains(Duration.ofSeconds(10));
        assertThat(config.heartbeatInterval).contains(Duration.ofMinutes(5));
        assertThat(config.shutdownMaxWait).contains(Duration.ofSeconds(30));
    }

    /** Absent knobs stay absent - validation must not turn "unset" into an error. */
    @Test
    void unset_knobs_remain_empty() {
        var config = JobsConfig.defaults();

        assertThat(config.pollingInterval).isEmpty();
        assertThat(config.heartbeatInterval).isEmpty();
        assertThat(config.shutdownMaxWait).isEmpty();
    }

    /** A sub-millisecond value is positive and legal; db-scheduler rounds it, it does not spin. */
    @Test
    void a_sub_millisecond_interval_is_accepted() {
        assertThat(jobsConfig().pollingInterval(Duration.ofNanos(500_000)).build().pollingInterval)
                .isPresent();
    }
}
