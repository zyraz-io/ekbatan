package io.ekbatan.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.naming.conventions.StringConvention;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the two faces of {@link EkbatanProperties}: the POJO contract (default namespace +
 * setter behaviour), and Micronaut {@link Environment} key resolution for the {@code ekbatan}
 * prefix.
 *
 * <p>{@code ekbatan.jobs.*} and {@code ekbatan.local-event-handler.*} are no longer bound here --
 * they moved to the core {@code JobsConfig} / {@code LocalEventHandlerConfig} types and are
 * exercised by {@link EkbatanCoreConfigurationTest}'s Jackson-hybrid binding path plus the
 * dedicated tests in the {@code ekbatan-distributed-jobs} / {@code ekbatan-local-event-handler}
 * modules.
 */
class EkbatanPropertiesTest {

    @Nested
    class PojoContract {

        @Test
        void namespaceDefaultsToDefault() {
            assertThat(new EkbatanProperties().getNamespace()).isEqualTo("default");
        }

        @Test
        void namespaceCanBeOverriddenViaSetter() {
            var props = new EkbatanProperties();
            props.setNamespace("wallets");
            assertThat(props.getNamespace()).isEqualTo("wallets");
        }
    }

    @Nested
    class EnvironmentKeyResolution {

        /**
         * Runs {@code body} against a Micronaut {@link Environment} fed with {@code props}, without
         * starting the bean container - same isolation trick as {@code EkbatanCoreConfigurationTest}.
         */
        private void withEnv(Map<String, Object> props, Consumer<Environment> body) {
            var ctx = ApplicationContext.builder()
                    .propertySources(PropertySource.of("test", props))
                    .build();
            try {
                var env = ctx.getEnvironment();
                env.start();
                body.accept(env);
            } finally {
                ctx.close();
            }
        }

        @Test
        void namespaceResolvesUnderTheEkbatanPrefix() {
            withEnv(Map.of("ekbatan.namespace", "wallets"), env -> {
                var flat = env.getProperties("ekbatan", StringConvention.CAMEL_CASE);
                assertThat(flat).containsEntry("namespace", "wallets");
            });
        }
    }
}
