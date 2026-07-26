package io.ekbatan.flyway;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.shard.ShardIdentifier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.junit.jupiter.api.Test;

class FlywayMigratorTest {

    @Test
    void should_detect_native_image_runtime_only() {
        var key = "org.graalvm.nativeimage.imagecode";
        var originalValue = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isFalse();

            System.setProperty(key, "buildtime");
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isFalse();

            System.setProperty(key, "runtime");
            assertThat(NativeImageFlywayResourceProvider.inNativeImage()).isTrue();
        } finally {
            if (originalValue == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, originalValue);
            }
        }
    }

    @Test
    void should_build_the_native_scanner_from_the_locations_the_customizer_left_behind() {
        // Goes through configure(...) rather than installNativeResourceProvider(...) directly: the
        // defect was the ordering of those two steps, so a test that performs them in its own order
        // proves nothing. Asserting on the scanner's captured locations, not the configuration's -
        // the configuration was always updated; the scanner was what kept the stale copy.
        var cfg = Flyway.configure().locations("classpath:db/migration");

        FlywayMigrator.applyCustomizerThenProvider(cfg, c -> c.locations("classpath:db/other"), true);

        assertThat(cfg.getResourceProvider()).isInstanceOf(NativeImageFlywayResourceProvider.class);
        var scanner = (NativeImageFlywayResourceProvider) cfg.getResourceProvider();
        assertThat(scanner.locations).extracting(Location::getDescriptor).containsExactly("classpath:db/other");
    }

    @Test
    void should_keep_a_resource_provider_the_customizer_supplied() {
        // The documented escape hatch. A plain reorder would have overwritten it.
        var cfg = Flyway.configure().locations("classpath:db/migration");
        ResourceProvider explicit = new ResourceProvider() {
            @Override
            public LoadableResource getResource(String name) {
                return null;
            }

            @Override
            public java.util.Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
                return java.util.List.of();
            }
        };
        cfg.resourceProvider(explicit);

        FlywayMigrator.installNativeResourceProvider(cfg, true);

        assertThat(cfg.getResourceProvider()).isSameAs(explicit);
    }

    @Test
    void should_not_install_the_native_scanner_outside_a_native_image() {
        var cfg = Flyway.configure().locations("classpath:db/migration");

        FlywayMigrator.installNativeResourceProvider(cfg, false);

        assertThat(cfg.getResourceProvider()).isNull();
    }

    @Test
    void should_build_targets_from_sharding_config_in_group_member_order() {
        var globalPrimary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://global:5432/wallet")
                .username("app")
                .password("secret")
                .build();
        var mexicoPrimary = dataSourceConfig()
                .jdbcUrl("jdbc:postgresql://mexico:5432/wallet")
                .username("app")
                .password("secret")
                .build();

        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("global")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .name("eu")
                                .primaryConfig(globalPrimary)
                                .build())
                        .build())
                .withGroup(shardGroupConfig()
                        .group(1)
                        .name("mexico")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(mexicoPrimary)
                                .build())
                        .build())
                .build();

        var targets = FlywayMigrator.targets(config);

        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).shard()).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(targets.get(0).name()).isEqualTo("global/eu");
        assertThat(targets.get(0).dataSourceConfig()).isSameAs(globalPrimary);
        assertThat(targets.get(1).shard()).isEqualTo(ShardIdentifier.of(1, 0));
        assertThat(targets.get(1).name()).isEqualTo("mexico/member-0");
        assertThat(targets.get(1).dataSourceConfig()).isSameAs(mexicoPrimary);
    }

    @Test
    void should_fail_when_no_targets_are_configured() {
        assertThatThrownBy(() -> FlywayMigrator.builder().migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one");
    }

    @Test
    void should_reject_blank_locations() {
        assertThatThrownBy(() -> FlywayMigrator.builder().locations(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locations");
    }
}
