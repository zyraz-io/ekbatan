package io.ekbatan.quarkus.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.config.ShardingConfig;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * In-process tests for {@link EkbatanCoreConfiguration#ekbatanShardingConfig} - the binding glue
 * that turns SmallRye Config's flat {@code ekbatan.sharding.*} property output into a
 * {@link ShardingConfig} via Jackson's {@code JavaPropsMapper}.
 *
 * <p>No Docker, no Arc, no Quarkus boot - these complement the end-to-end {@code @QuarkusTest} in
 * {@code ekbatan-integration-tests-di-quarkus} by exhaustively covering branches that test doesn't
 * reach: optional-present vs optional-absent, multi-named-configs, type coercion, and the
 * error-path contracts.
 *
 * <p>The helper {@link #registerConfig} builds a {@link SmallRyeConfig} from in-memory properties
 * and registers it with {@link ConfigProviderResolver} so the producer method's
 * {@code ConfigProvider.getConfig()} call resolves to our test instance. The {@link AfterEach}
 * releases it - SmallRye throws if a config is already registered for the same classloader, and
 * we don't want to leak between tests.
 *
 * <p>Casing note: SmallRye stores property names verbatim; EkbatanCoreConfiguration folds kebab
 * segments to camelCase via {@code PropertyKeyNormalizer} before handing to Jackson. So users
 * may write either spelling (or mix them) and both bind. The tests below pin both branches.
 */
class EkbatanCoreConfigurationTest {

    private SmallRyeConfig registered;

    @AfterEach
    void releaseRegistered() {
        if (registered != null) {
            ConfigProviderResolver.instance().releaseConfig(registered);
            registered = null;
        }
    }

    /**
     * Registers {@code props} as the active {@link SmallRyeConfig} for the current classloader so
     * production code's {@code ConfigProvider.getConfig()} resolves to it.
     */
    private void registerConfig(Map<String, String> props) {
        var resolver = ConfigProviderResolver.instance();
        var cl = Thread.currentThread().getContextClassLoader();
        // Defensive: release any leftover config (auto-discovered default or a stale registration
        // from a prior crashed test). SmallRye throws if a config is already registered for this
        // classloader, so this guard keeps the test class hermetic.
        try {
            resolver.releaseConfig(resolver.getConfig(cl));
        } catch (Exception ignored) {
            // No config registered, or release failed - fine, we'll register fresh below.
        }
        registered = new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(props, "test", 1000))
                .build();
        resolver.registerConfig(registered, cl);
    }

    private static ShardingConfig bind() {
        return new EkbatanCoreConfiguration().ekbatanShardingConfig();
    }

    /** Minimal single-shard properties in camelCase - the canonical form handed to Jackson after normalization. */
    private static Map<String, String> minimalCamelCase() {
        var p = new LinkedHashMap<String, String>();
        p.put("ekbatan.sharding.defaultShard.group", "0");
        p.put("ekbatan.sharding.defaultShard.member", "0");
        p.put("ekbatan.sharding.groups[0].group", "0");
        p.put("ekbatan.sharding.groups[0].name", "default");
        p.put("ekbatan.sharding.groups[0].members[0].member", "0");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/db");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", "u");
        p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", "p");
        return p;
    }

    @Nested
    class Casing {

        @Test
        void bindsAllCamelCaseKeys() {
            registerConfig(minimalCamelCase());
            var cfg = bind();
            var primary = cfg.groups.get(0).members.get(0).configs.get("primaryConfig");
            assertThat(primary).isNotNull();
            assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://h/db");
            assertThat(primary.username).isEqualTo("u");
            assertThat(primary.password).isEqualTo("p");
        }

        @Test
        void bindsAllKebabCaseKeys() {
            // EkbatanCoreConfiguration runs PropertyKeyNormalizer.kebabToCamel on each key segment
            // before handing it to Jackson - kebab-spelled leaves and kebab-spelled map keys both
            // resolve to the same canonical camelCase form, so the strict mapper sees the same
            // input as the all-camelCase fixture above.
            var p = new LinkedHashMap<String, String>();
            p.put("ekbatan.sharding.default-shard.group", "0");
            p.put("ekbatan.sharding.default-shard.member", "0");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            p.put("ekbatan.sharding.groups[0].members[0].member", "0");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.jdbc-url", "jdbc:postgresql://h/db");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.username", "u");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primary-config.password", "p");
            registerConfig(p);

            var cfg = bind();
            assertThat(cfg.groups.get(0).members.get(0).configs)
                    .containsKey("primaryConfig")
                    .doesNotContainKey("primary-config");
            assertThat(cfg.groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                    .isEqualTo("jdbc:postgresql://h/db");
        }

        @Test
        void bindsMixedCamelAndKebabCaseInSameTree() {
            // Mixing styles inside one YAML/properties file is the whole point of the normaliser -
            // both spellings collapse to the same canonical camelCase property.
            var p = new LinkedHashMap<String, String>();
            p.put("ekbatan.sharding.defaultShard.group", "0");
            p.put("ekbatan.sharding.defaultShard.member", "0");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            p.put("ekbatan.sharding.groups[0].members[0].member", "0");
            // Mixed: camelCase configs-map key, kebab-case leaf properties.
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbc-url", "jdbc:postgresql://h/db");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username", "u");
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password", "p");
            registerConfig(p);

            assertThat(bind().groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                    .isEqualTo("jdbc:postgresql://h/db");
        }
    }

    @Nested
    class OptionalFields {

        @Test
        void optionalDataSourceFields_areEmpty_whenAbsent() {
            registerConfig(minimalCamelCase());
            var primary = bind().groups.get(0).members.get(0).configs.get("primaryConfig");
            assertThat(primary.driverClassName).isEmpty();
            assertThat(primary.minimumIdle).isEmpty();
            assertThat(primary.idleTimeout).isEmpty();
            assertThat(primary.leakDetectionThreshold).isEmpty();
        }

        @Test
        void optionalDataSourceFields_areBound_whenPresent() {
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.";
            p.put(prefix + "driverClassName", "org.postgresql.Driver");
            p.put(prefix + "minimumIdle", "2");
            p.put(prefix + "idleTimeout", "600000");
            p.put(prefix + "leakDetectionThreshold", "60000");
            registerConfig(p);

            var primary = bind().groups.get(0).members.get(0).configs.get("primaryConfig");
            assertThat(primary.driverClassName).contains("org.postgresql.Driver");
            assertThat(primary.minimumIdle).contains(2);
            assertThat(primary.idleTimeout).contains(600_000L);
            assertThat(primary.leakDetectionThreshold).contains(60_000L);
        }

        @Test
        void optionalMemberName_isEmpty_whenAbsent() {
            registerConfig(minimalCamelCase());
            assertThat(bind().groups.get(0).members.get(0).name).isEmpty();
        }

        @Test
        void optionalMemberName_isBound_whenPresent() {
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].name", "primary-eu-west-1");
            registerConfig(p);
            assertThat(bind().groups.get(0).members.get(0).name).contains("primary-eu-west-1");
        }
    }

    @Nested
    class Topology {

        @Test
        void bindsMultipleNamedConfigsForOneMember() {
            // A member can carry several named DataSourceConfigs - e.g. "primaryConfig" for app
            // queries and "jobsConfig" for the distributed-jobs scheduler. Both must arrive as
            // distinct keys in the configs map.
            var p = minimalCamelCase();
            var prefix = "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.";
            p.put(prefix + "jdbcUrl", "jdbc:postgresql://h/db_jobs");
            p.put(prefix + "username", "uj");
            p.put(prefix + "password", "pj");
            registerConfig(p);

            var configs = bind().groups.get(0).members.get(0).configs;
            assertThat(configs).containsOnlyKeys("primaryConfig", "jobsConfig");
            assertThat(configs.get("primaryConfig").jdbcUrl).isEqualTo("jdbc:postgresql://h/db");
            assertThat(configs.get("jobsConfig").jdbcUrl).isEqualTo("jdbc:postgresql://h/db_jobs");
            assertThat(configs.get("jobsConfig").username).isEqualTo("uj");
        }

        @Test
        void bindsMultiGroupMultiMemberTopology() {
            var p = new LinkedHashMap<String, String>();
            p.put("ekbatan.sharding.defaultShard.group", "1");
            p.put("ekbatan.sharding.defaultShard.member", "0");
            for (int g = 0; g < 2; g++) {
                p.put("ekbatan.sharding.groups[" + g + "].group", String.valueOf(g));
                p.put("ekbatan.sharding.groups[" + g + "].name", "g" + g);
                for (int m = 0; m < 2; m++) {
                    var prefix = "ekbatan.sharding.groups[" + g + "].members[" + m + "].";
                    p.put(prefix + "member", String.valueOf(m));
                    p.put(prefix + "configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/g" + g + "m" + m);
                    p.put(prefix + "configs.primaryConfig.username", "u");
                    p.put(prefix + "configs.primaryConfig.password", "p");
                }
            }
            registerConfig(p);

            var cfg = bind();
            assertThat(cfg.defaultShard.group).isEqualTo(1);
            assertThat(cfg.defaultShard.member).isZero();
            assertThat(cfg.groups).hasSize(2);
            assertThat(cfg.groups.get(0).members).hasSize(2);
            assertThat(cfg.groups.get(1).members).hasSize(2);
            assertThat(cfg.groups.get(0).members.get(0).configs.get("primaryConfig").jdbcUrl)
                    .isEqualTo("jdbc:postgresql://h/g0m0");
            assertThat(cfg.groups.get(1).members.get(1).configs.get("primaryConfig").jdbcUrl)
                    .isEqualTo("jdbc:postgresql://h/g1m1");
        }

        @Test
        void bindsArrayIndicesBeyondZero() {
            // Defensive check against any off-by-one in JavaPropsMapper's [idx] array marker.
            var p = new LinkedHashMap<String, String>();
            p.put("ekbatan.sharding.defaultShard.group", "0");
            p.put("ekbatan.sharding.defaultShard.member", "2");
            p.put("ekbatan.sharding.groups[0].group", "0");
            p.put("ekbatan.sharding.groups[0].name", "default");
            for (int m = 0; m < 3; m++) {
                var prefix = "ekbatan.sharding.groups[0].members[" + m + "].";
                p.put(prefix + "member", String.valueOf(m));
                p.put(prefix + "configs.primaryConfig.jdbcUrl", "jdbc:postgresql://h/m" + m);
                p.put(prefix + "configs.primaryConfig.username", "u");
                p.put(prefix + "configs.primaryConfig.password", "p");
            }
            registerConfig(p);

            var cfg = bind();
            assertThat(cfg.groups.get(0).members).hasSize(3);
            assertThat(cfg.groups.get(0).members.get(2).member).isEqualTo(2);
            assertThat(cfg.groups.get(0).members.get(2).configs.get("primaryConfig").jdbcUrl)
                    .isEqualTo("jdbc:postgresql://h/m2");
        }
    }

    @Nested
    class TypeCoercion {

        @Test
        void coercesNumericStrings() {
            // SmallRye hands us string values; Jackson must coerce them to int / Long.
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize", "17");
            registerConfig(p);

            assertThat(bind().groups.get(0).members.get(0).configs.get("primaryConfig").maximumPoolSize)
                    .isEqualTo(17);
        }
    }

    @Nested
    class ErrorPaths {

        @Test
        void throwsWhenEkbatanShardingIsAbsent() {
            registerConfig(Map.of());
            assertThatThrownBy(EkbatanCoreConfigurationTest::bind)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ekbatan.sharding")
                    .hasMessageContaining("primaryConfig");
        }

        @Test
        void failsOnUnknownPropertySurfacesTypos() {
            // FAIL_ON_UNKNOWN_PROPERTIES on the private mapper surfaces typos at startup rather
            // than silently dropping them - that's the whole point of the strict mapper.
            var p = minimalCamelCase();
            p.put("ekbatan.sharding.groups[0].members[0].configs.primaryConfig.notARealField", "x");
            registerConfig(p);

            assertThatThrownBy(EkbatanCoreConfigurationTest::bind)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.sharding'");
        }
    }

    @Nested
    class JobsConfigBinding {

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            registerConfig(Map.of());
            var cfg = new EkbatanCoreConfiguration().ekbatanJobsConfig();
            assertThat(cfg.pollingInterval).isEmpty();
            assertThat(cfg.heartbeatInterval).isEmpty();
            assertThat(cfg.shutdownMaxWait).isEmpty();
        }

        @Test
        void bindsKebabCaseKeysWithIso8601Durations() {
            registerConfig(Map.of(
                    "ekbatan.jobs.polling-interval", "PT5S",
                    "ekbatan.jobs.heartbeat-interval", "PT3S",
                    "ekbatan.jobs.shutdown-max-wait", "PT30S"));

            var cfg = new EkbatanCoreConfiguration().ekbatanJobsConfig();
            assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
        }

        @Test
        void bindsCamelCaseKeys() {
            registerConfig(Map.of(
                    "ekbatan.jobs.pollingInterval", "PT5S",
                    "ekbatan.jobs.heartbeatInterval", "PT3S",
                    "ekbatan.jobs.shutdownMaxWait", "PT30S"));

            var cfg = new EkbatanCoreConfiguration().ekbatanJobsConfig();
            assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
        }

        @Test
        void bindsMixedKebabAndCamelCaseKeys() {
            // PropertyKeyNormalizer collapses both spellings to the same canonical form,
            // so a user can mix them in the same config file with no surprises.
            registerConfig(Map.of(
                    "ekbatan.jobs.polling-interval", "PT5S", // kebab
                    "ekbatan.jobs.heartbeatInterval", "PT3S", // camel
                    "ekbatan.jobs.shutdown-max-wait", "PT30S")); // kebab

            var cfg = new EkbatanCoreConfiguration().ekbatanJobsConfig();
            assertThat(cfg.pollingInterval).contains(java.time.Duration.ofSeconds(5));
            assertThat(cfg.heartbeatInterval).contains(java.time.Duration.ofSeconds(3));
            assertThat(cfg.shutdownMaxWait).contains(java.time.Duration.ofSeconds(30));
        }

        @Test
        void failsOnUnknownProperty() {
            registerConfig(Map.of("ekbatan.jobs.not-a-real-field", "x"));
            assertThatThrownBy(() -> new EkbatanCoreConfiguration().ekbatanJobsConfig())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.jobs'");
        }
    }

    @Nested
    class LocalEventHandlerConfigBinding {

        @Test
        void returnsDefaults_whenSubtreeAbsent() {
            registerConfig(Map.of());
            var cfg = new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig();
            assertThat(cfg.fanoutPollDelay).isEmpty();
            assertThat(cfg.handling.enabled).isFalse();
        }

        @Test
        void bindsFlatAndNestedKnobs() {
            registerConfig(Map.of(
                    "ekbatan.local-event-handler.fanout-poll-delay", "PT0.2S",
                    "ekbatan.local-event-handler.fanout-batch-size", "100",
                    "ekbatan.local-event-handler.handling-poll-delay", "PT0.15S",
                    "ekbatan.local-event-handler.handling.enabled", "true"));

            var cfg = new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig();
            assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCaseKeys() {
            registerConfig(Map.of(
                    "ekbatan.local-event-handler.fanoutPollDelay", "PT0.2S",
                    "ekbatan.local-event-handler.fanoutBatchSize", "100",
                    "ekbatan.local-event-handler.handlingPollDelay", "PT0.15S",
                    "ekbatan.local-event-handler.handling.enabled", "true"));

            var cfg = new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig();
            assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCasedParentSegment() {
            // The PARENT (`localEventHandler` vs `local-event-handler`) must also normalise so
            // users can write the entire path in either style.
            registerConfig(Map.of(
                    "ekbatan.localEventHandler.fanoutPollDelay", "PT0.2S",
                    "ekbatan.localEventHandler.fanoutBatchSize", "100",
                    "ekbatan.localEventHandler.handlingPollDelay", "PT0.15S",
                    "ekbatan.localEventHandler.handling.enabled", "true"));

            var cfg = new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig();
            assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void bindsCamelCasedParentSegmentWithKebabLeaves() {
            // Cross-form: camelCase parent + kebab leaves. Both halves get normalised
            // independently so the configuration still binds.
            registerConfig(Map.of(
                    "ekbatan.localEventHandler.fanout-poll-delay", "PT0.2S",
                    "ekbatan.localEventHandler.fanout-batch-size", "100",
                    "ekbatan.localEventHandler.handling-poll-delay", "PT0.15S",
                    "ekbatan.localEventHandler.handling.enabled", "true"));

            var cfg = new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig();
            assertThat(cfg.fanoutPollDelay).contains(java.time.Duration.ofMillis(200));
            assertThat(cfg.fanoutBatchSize).contains(100);
            assertThat(cfg.handlingPollDelay).contains(java.time.Duration.ofMillis(150));
            assertThat(cfg.handling.enabled).isTrue();
        }

        @Test
        void failsOnUnknownProperty() {
            registerConfig(Map.of("ekbatan.local-event-handler.not-a-real-field", "x"));
            assertThatThrownBy(() -> new EkbatanCoreConfiguration().ekbatanLocalEventHandlerConfig())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Failed to bind 'ekbatan.local-event-handler'");
        }
    }
}
