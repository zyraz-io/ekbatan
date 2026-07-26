package io.ekbatan.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * In-process proof that {@link EkbatanPropertyNameInterceptor} is discovered via ServiceLoader and
 * makes both spellings of an {@code ekbatan.*} key resolve on a plain LOOKUP - the code path
 * {@code @IfBuildProperty} uses. No Quarkus boot.
 */
class EkbatanPropertyNameInterceptorTest {

    private static final String KEBAB = "ekbatan.local-event-handler.handling.enabled";
    private static final String CAMEL = "ekbatan.localEventHandler.handling.enabled";

    /** Mirrors how Quarkus assembles config: default + ServiceLoader-discovered interceptors. */
    private static SmallRyeConfig configWith(Map<String, String> props) {
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .addDiscoveredInterceptors()
                .withSources(new PropertiesConfigSource(props, "test", 100))
                .build();
    }

    @Test
    void interceptor_is_discovered_by_service_loader() {
        var found = java.util.ServiceLoader.load(
                        io.smallrye.config.ConfigSourceInterceptor.class,
                        Thread.currentThread().getContextClassLoader())
                .stream()
                .map(p -> p.type().getName())
                .toList();
        assertThat(found).contains(EkbatanPropertyNameInterceptor.class.getName());
    }

    @Test
    void kebab_lookup_finds_camel_source() {
        var config = configWith(Map.of(CAMEL, "true"));
        assertThat(config.getOptionalValue(KEBAB, String.class)).contains("true");
    }

    @Test
    void kebab_lookup_finds_kebab_source() {
        var config = configWith(Map.of(KEBAB, "true"));
        assertThat(config.getOptionalValue(KEBAB, String.class)).contains("true");
    }

    @Test
    void camel_lookup_finds_kebab_source() {
        var config = configWith(Map.of(KEBAB, "true"));
        assertThat(config.getOptionalValue(CAMEL, String.class)).contains("true");
    }

    @Test
    void absent_property_stays_absent() {
        var config = configWith(Map.of("ekbatan.namespace", "demo"));
        assertThat(config.getOptionalValue(KEBAB, String.class)).isEmpty();
        assertThat(config.getOptionalValue(CAMEL, String.class)).isEmpty();
    }

    @Test
    void explicit_spelling_wins_over_alias() {
        var config = configWith(Map.of(KEBAB, "false", CAMEL, "true"));
        assertThat(config.getOptionalValue(KEBAB, String.class)).contains("false");
        assertThat(config.getOptionalValue(CAMEL, String.class)).contains("true");
    }

    @Test
    void non_ekbatan_keys_are_untouched() {
        var config = configWith(Map.of("quarkus.someCamelKey", "x"));
        assertThat(config.getOptionalValue("quarkus.some-camel-key", String.class))
                .isEmpty();
    }

    @Test
    void kebab_lookup_finds_a_mixed_spelling_source() {
        // `local-eventHandler` - half dashes, half capitals. Neither of the two "extreme" spellings.
        var config = configWith(Map.of("ekbatan.local-eventHandler.handling.enabled", "true"));
        assertThat(config.getOptionalValue("ekbatan.local-event-handler.handling.enabled", String.class))
                .contains("true");
    }

    @Test
    void kebab_lookup_finds_the_other_mixed_spelling_source() {
        var config = configWith(Map.of("ekbatan.localEvent-handler.handling.enabled", "true"));
        assertThat(config.getOptionalValue("ekbatan.local-event-handler.handling.enabled", String.class))
                .contains("true");
    }

    @Test
    void camel_lookup_finds_a_mixed_spelling_source() {
        var config = configWith(Map.of("ekbatan.local-eventHandler.handling.enabled", "true"));
        assertThat(config.getOptionalValue("ekbatan.localEventHandler.handling.enabled", String.class))
                .contains("true");
    }

    @Test
    void mixed_lookup_finds_a_kebab_source() {
        // The mirror: a caller asking with a mixed spelling still resolves.
        var config = configWith(Map.of("ekbatan.local-event-handler.handling.enabled", "true"));
        assertThat(config.getOptionalValue("ekbatan.local-eventHandler.handling.enabled", String.class))
                .contains("true");
    }

    @Test
    void a_different_key_is_not_matched_by_the_scan() {
        // Guard against the fold being too eager: these are genuinely different keys, not spellings
        // of one key, so the scan must not connect them.
        var config = configWith(Map.of("ekbatan.local-event-handler.handling.enabled", "true"));
        assertThat(config.getOptionalValue("ekbatan.local-event-handler.fanout.enabled", String.class))
                .isEmpty();
    }
}
