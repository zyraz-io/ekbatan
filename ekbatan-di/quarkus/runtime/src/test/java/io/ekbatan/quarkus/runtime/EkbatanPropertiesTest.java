package io.ekbatan.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers SmallRye {@code @ConfigMapping} resolution for {@link EkbatanProperties}, which is now
 * a thin interface holding only {@code ekbatan.namespace}. The {@code ekbatan.jobs.*} and
 * {@code ekbatan.local-event-handler.*} subtrees moved to {@code JobsConfig} /
 * {@code LocalEventHandlerConfig} in their respective core modules and are exercised by
 * {@link EkbatanCoreConfigurationTest}'s Jackson-hybrid binding path. Sharding has its own
 * test there too.
 *
 * <p>We bypass Quarkus boot entirely - {@link SmallRyeConfigBuilder#withMapping} materialises
 * the proxy from in-memory properties so the namespace branch is verified in isolation.
 */
class EkbatanPropertiesTest {

    /**
     * Builds a fresh, unregistered SmallRyeConfig with {@link EkbatanProperties} registered as a
     * mapping and returns the materialised proxy.
     */
    private static EkbatanProperties bindMapping(Map<String, String> props) {
        var config = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(props, "test", 1000))
                .withMapping(EkbatanProperties.class)
                .build();
        return config.getConfigMapping(EkbatanProperties.class);
    }

    @Test
    void namespaceDefaultsToDefault() {
        assertThat(bindMapping(Map.of()).namespace()).isEqualTo("default");
    }

    @Test
    void namespaceCanBeOverridden() {
        assertThat(bindMapping(Map.of("ekbatan.namespace", "wallets")).namespace())
                .isEqualTo("wallets");
    }
}
