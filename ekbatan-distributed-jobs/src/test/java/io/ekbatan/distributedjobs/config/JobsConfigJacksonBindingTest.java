package io.ekbatan.distributedjobs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the Jackson inline annotations on {@link JobsConfig} via a {@link JsonMapper}
 * configured the same way the per-DI {@code ekbatanJobsConfig} producers configure their
 * mapper (kebab-case naming strategy + strict {@code FAIL_ON_UNKNOWN_PROPERTIES}). Each DI's
 * producer is exercised end-to-end in its own integration test; this slice verifies the
 * annotation contract in isolation without booting a DI container.
 */
class JobsConfigJacksonBindingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .build();

    @Test
    void defaults_returnsEmptyOptionals() {
        var cfg = JobsConfig.defaults();
        assertThat(cfg.pollingInterval).isEmpty();
        assertThat(cfg.heartbeatInterval).isEmpty();
        assertThat(cfg.shutdownMaxWait).isEmpty();
    }

    @Test
    void builder_setsOptionalDurations() {
        var cfg = JobsConfig.jobsConfig()
                .pollingInterval(Duration.ofSeconds(5))
                .heartbeatInterval(Duration.ofSeconds(3))
                .shutdownMaxWait(Duration.ofSeconds(30))
                .build();
        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
        assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
    }

    @Test
    void builder_treatsNullAsAbsent() {
        var cfg = JobsConfig.jobsConfig().pollingInterval(null).build();
        assertThat(cfg.pollingInterval).isEmpty();
    }

    @Test
    void jacksonBindsKebabCaseKeysToCamelCaseMethods() {
        // The canonical user-facing form: kebab-case keys + ISO-8601 Duration values - what each
        // DI's producer feeds into Jackson after stripping the ekbatan.jobs. prefix.
        var tree = new LinkedHashMap<String, Object>();
        tree.put("polling-interval", "PT5S");
        tree.put("heartbeat-interval", "PT3S");
        tree.put("shutdown-max-wait", "PT30S");

        var cfg = MAPPER.convertValue(tree, JobsConfig.class);

        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
        assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
    }

    @Test
    void jacksonLeavesUnsetFieldsEmpty() {
        var cfg = MAPPER.convertValue(Map.of("polling-interval", "PT5S"), JobsConfig.class);
        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).isEmpty();
        assertThat(cfg.shutdownMaxWait).isEmpty();
    }

    @Test
    void jackson_failsFastOnUnknownProperty() {
        // FAIL_ON_UNKNOWN_PROPERTIES surfaces typos at startup rather than silently dropping them.
        var tree = Map.of("polling-interval", "PT5S", "not-a-real-field", "x");

        assertThatThrownBy(() -> MAPPER.convertValue(tree, JobsConfig.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("not-a-real-field");
    }
}
