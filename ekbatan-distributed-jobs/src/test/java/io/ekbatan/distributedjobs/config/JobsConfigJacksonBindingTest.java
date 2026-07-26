package io.ekbatan.distributedjobs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.config.PropertyKeyNormalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.dataformat.javaprop.JavaPropsMapper;

/**
 * Binds {@link JobsConfig} the way the DI producers actually bind it.
 *
 * <p>This used to build a {@code JsonMapper} with a {@code KEBAB_CASE} naming strategy and claim
 * in its javadoc to be "configured the same way the per-DI producers configure their mapper".
 * Production does neither of those things: it uses a {@link JavaPropsMapper} with <em>no</em>
 * naming strategy, and folds kebab-case keys to camelCase beforehand via
 * {@link PropertyKeyNormalizer#kebabToCamel}. The test was exercising a configuration nobody runs,
 * and would have kept passing had the real path broken.
 *
 * <p>The difference is not cosmetic. A {@code KEBAB_CASE} strategy makes {@code pollingInterval}
 * an <em>unknown</em> property - and that camelCase form is exactly what the normalizer emits on
 * every real binding, so the two configurations disagree about the only keys production ever sees.
 *
 * <p>Each DI's producer is still exercised end-to-end in its own integration test; this slice pins
 * the annotation contract without booting a container.
 */
class JobsConfigJacksonBindingTest {

    /** The same mapper {@code EkbatanCoreConfiguration.bindSubtree} builds. */
    private static final JavaPropsMapper MAPPER = JavaPropsMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
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

    /**
     * What the producers hand Jackson: camelCase keys, prefix already stripped. The kebab form a
     * user writes in {@code application.yml} has been folded away by this point.
     */
    @Test
    void camel_case_keys_bind_because_that_is_what_the_normalizer_emits() {
        var cfg = bind(Map.of(
                "pollingInterval", "PT5S",
                "heartbeatInterval", "PT3S",
                "shutdownMaxWait", "PT30S"));

        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
        assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
    }

    /**
     * The user-facing form, through the real folding step rather than a naming strategy standing in
     * for it. Running the keys through {@link PropertyKeyNormalizer} is the part production does
     * and the old test skipped.
     */
    @Test
    void kebab_case_keys_bind_once_the_normalizer_has_folded_them() {
        var raw = Map.of(
                "polling-interval", "PT5S",
                "heartbeat-interval", "PT3S",
                "shutdown-max-wait", "PT30S");
        var folded = new LinkedHashMap<String, String>();
        raw.forEach((key, value) -> folded.put(PropertyKeyNormalizer.kebabToCamel(key), value));

        var cfg = bind(folded);

        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).contains(Duration.ofSeconds(3));
        assertThat(cfg.shutdownMaxWait).contains(Duration.ofSeconds(30));
    }

    @Test
    void unset_knobs_stay_empty() {
        var cfg = bind(Map.of("pollingInterval", "PT5S"));

        assertThat(cfg.pollingInterval).contains(Duration.ofSeconds(5));
        assertThat(cfg.heartbeatInterval).isEmpty();
        assertThat(cfg.shutdownMaxWait).isEmpty();
    }

    /** FAIL_ON_UNKNOWN_PROPERTIES surfaces a typo at startup rather than silently dropping it. */
    @Test
    void an_unknown_property_fails_fast() {
        assertThatThrownBy(() -> bind(Map.of("pollingInterval", "PT5S", "notARealField", "x")))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("notARealField");
    }

    /**
     * Spring shorthand is rejected, which is why every documented value is ISO-8601. Jackson binds
     * a {@link Duration} with {@code Duration.parse}, and {@code 10s} is not valid ISO-8601 - so a
     * copied snippet fails at startup rather than being silently misread.
     */
    @Test
    void spring_style_duration_shorthand_is_rejected() {
        assertThatThrownBy(() -> bind(Map.of("pollingInterval", "10s"))).hasMessageContaining("10s");
    }

    private static JobsConfig bind(Map<String, String> properties) {
        var props = new Properties();
        props.putAll(properties);
        try {
            return MAPPER.readPropertiesAs(props, JobsConfig.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
