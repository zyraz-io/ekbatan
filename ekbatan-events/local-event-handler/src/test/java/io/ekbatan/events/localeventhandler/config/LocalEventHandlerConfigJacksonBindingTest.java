package io.ekbatan.events.localeventhandler.config;

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
 * Exercises the Jackson inline annotations on {@link LocalEventHandlerConfig} (and its nested
 * {@link LocalEventHandlerConfig.HandlingConfig}) via a {@link JsonMapper} configured the same
 * way the per-DI {@code ekbatanLocalEventHandlerConfig} producers configure their mapper
 * (kebab-case naming strategy + strict {@code FAIL_ON_UNKNOWN_PROPERTIES}). Each DI's producer is
 * exercised end-to-end in its own integration test; this slice verifies the annotation contract
 * in isolation without booting a DI container.
 */
class LocalEventHandlerConfigJacksonBindingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .build();

    @Test
    void defaults_returnsEmptyOptionalsAndDisabledHandling() {
        var cfg = LocalEventHandlerConfig.defaults();
        assertThat(cfg.fanoutPollDelay).isEmpty();
        assertThat(cfg.fanoutBatchSize).isEmpty();
        assertThat(cfg.handlingPollDelay).isEmpty();
        assertThat(cfg.handlingBatchSize).isEmpty();
        assertThat(cfg.handlingMaxBackoffCap).isEmpty();
        assertThat(cfg.handlingRetentionWindow).isEmpty();
        assertThat(cfg.handling).isNotNull();
        assertThat(cfg.handling.enabled).isFalse();
    }

    @Test
    void builder_setsAllKnobs() {
        var cfg = LocalEventHandlerConfig.localEventHandlerConfig()
                .fanoutPollDelay(Duration.ofMillis(200))
                .fanoutBatchSize(100)
                .handlingPollDelay(Duration.ofMillis(150))
                .handlingBatchSize(50)
                .handlingMaxBackoffCap(Duration.ofSeconds(10))
                .handlingRetentionWindow(Duration.ofHours(24))
                .handling(LocalEventHandlerConfig.HandlingConfig.handlingConfig()
                        .enabled(true)
                        .build())
                .build();

        assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
        assertThat(cfg.fanoutBatchSize).contains(100);
        assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
        assertThat(cfg.handlingBatchSize).contains(50);
        assertThat(cfg.handlingMaxBackoffCap).contains(Duration.ofSeconds(10));
        assertThat(cfg.handlingRetentionWindow).contains(Duration.ofHours(24));
        assertThat(cfg.handling.enabled).isTrue();
    }

    @Test
    void builder_handlingNullKeepsDefault() {
        // Defensive: passing a null handling subtree shouldn't NPE later.
        var cfg =
                LocalEventHandlerConfig.localEventHandlerConfig().handling(null).build();
        assertThat(cfg.handling).isNotNull();
        assertThat(cfg.handling.enabled).isFalse();
    }

    @Test
    void jacksonBindsKebabCaseKeysToCamelCaseMethods() {
        // What each DI's producer feeds to Jackson after stripping the prefix -- kebab keys at
        // every level (including the nested handling sub-tree).
        var tree = new LinkedHashMap<String, Object>();
        tree.put("fanout-poll-delay", "PT0.2S");
        tree.put("fanout-batch-size", "100");
        tree.put("handling-poll-delay", "PT0.15S");
        tree.put("handling-batch-size", "50");
        tree.put("handling-max-backoff-cap", "PT10S");
        tree.put("handling-retention-window", "PT24H");
        tree.put("handling", Map.of("enabled", "true"));

        var cfg = MAPPER.convertValue(tree, LocalEventHandlerConfig.class);

        assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
        assertThat(cfg.fanoutBatchSize).contains(100);
        assertThat(cfg.handlingPollDelay).contains(Duration.ofMillis(150));
        assertThat(cfg.handlingBatchSize).contains(50);
        assertThat(cfg.handlingMaxBackoffCap).contains(Duration.ofSeconds(10));
        assertThat(cfg.handlingRetentionWindow).contains(Duration.ofHours(24));
        assertThat(cfg.handling.enabled).isTrue();
    }

    @Test
    void jacksonLeavesUnsetFieldsEmpty() {
        var cfg = MAPPER.convertValue(Map.of("fanout-poll-delay", "PT0.2S"), LocalEventHandlerConfig.class);
        assertThat(cfg.fanoutPollDelay).contains(Duration.ofMillis(200));
        assertThat(cfg.fanoutBatchSize).isEmpty();
        assertThat(cfg.handlingPollDelay).isEmpty();
        assertThat(cfg.handling.enabled).isFalse();
    }

    @Test
    void jackson_failsFastOnUnknownProperty() {
        var tree = Map.of("fanout-poll-delay", "PT0.2S", "not-a-real-field", "x");

        assertThatThrownBy(() -> MAPPER.convertValue(tree, LocalEventHandlerConfig.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("not-a-real-field");
    }
}
