package io.ekbatan.bootstrap.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.config.ShardingConfig;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Exercises the Jackson mix-ins by deserializing representative YAML straight into the existing
 * builder-pattern config classes. If this passes, the framework integrations (Spring/Quarkus/
 * Micronaut) can use the same {@link EkbatanConfigJacksonModule} as their YAML → ShardingConfig
 * adapter, without modifying the four config classes.
 */
class EkbatanConfigJacksonModuleTest {

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .addModule(new EkbatanConfigJacksonModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    void should_deserialize_full_sharding_config_from_yaml() {
        // GIVEN
        var yaml = """
                defaultShard:
                  group: 0
                  member: 0
                groups:
                  - group: 0
                    name: global
                    members:
                      - member: 0
                        name: global-eu-1
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://primary-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 20
                            minimumIdle: 5
                          secondaryConfig:
                            jdbcUrl: jdbc:postgresql://replica-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 10
                          lockConfig:
                            jdbcUrl: jdbc:postgresql://locks-eu-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 50
                            leakDetectionThreshold: 0
                  - group: 1
                    name: mexico
                    members:
                      - member: 0
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://primary-mx-1:5432/db
                            username: app
                            password: secret
                            maximumPoolSize: 8
                """;

        // WHEN
        var config = MAPPER.readValue(yaml, ShardingConfig.class);

        // THEN — top level
        assertThat(config.defaultShard).isEqualTo(ShardIdentifier.of(0, 0));
        assertThat(config.groups).hasSize(2);

        // AND — group 0
        var globalGroup = config.groups.get(0);
        assertThat(globalGroup.group).isEqualTo(0);
        assertThat(globalGroup.name).isEqualTo("global");
        assertThat(globalGroup.members).hasSize(1);

        var globalMember = globalGroup.members.get(0);
        assertThat(globalMember.member).isEqualTo(0);
        assertThat(globalMember.name).contains("global-eu-1");

        var primary = globalMember.primaryConfig();
        assertThat(primary.jdbcUrl).isEqualTo("jdbc:postgresql://primary-eu-1:5432/db");
        assertThat(primary.username).isEqualTo("app");
        assertThat(primary.password).isEqualTo("secret");
        assertThat(primary.maximumPoolSize).isEqualTo(20);
        assertThat(primary.minimumIdle).contains(5);
        assertThat(primary.dialect).isEqualTo(SQLDialect.POSTGRES);

        assertThat(globalMember.secondaryConfig()).isPresent();
        assertThat(globalMember.secondaryConfig().get().jdbcUrl).isEqualTo("jdbc:postgresql://replica-eu-1:5432/db");

        var lockConfig = globalMember.configFor("lockConfig");
        assertThat(lockConfig).isPresent();
        assertThat(lockConfig.get().maximumPoolSize).isEqualTo(50);
        assertThat(lockConfig.get().leakDetectionThreshold).contains(0L);

        // AND — group 1
        var mexicoGroup = config.groups.get(1);
        assertThat(mexicoGroup.group).isEqualTo(1);
        assertThat(mexicoGroup.name).isEqualTo("mexico");

        var mexicoMember = mexicoGroup.members.get(0);
        assertThat(mexicoMember.name).isEmpty();
        assertThat(mexicoMember.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://primary-mx-1:5432/db");
        assertThat(mexicoMember.secondaryConfig()).isEmpty();
        assertThat(mexicoMember.configFor("lockConfig")).isEmpty();
    }

    @Test
    void should_handle_member_with_only_primary_config() {
        // GIVEN
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: solo
                    members:
                      - member: 0
                        configs:
                          primaryConfig:
                            jdbcUrl: jdbc:postgresql://only:5432/db
                            username: app
                            password: secret
                """;

        // WHEN
        var config = MAPPER.readValue(yaml, ShardingConfig.class);

        // THEN
        var member = config.groups.get(0).members.get(0);
        assertThat(member.primaryConfig().jdbcUrl).isEqualTo("jdbc:postgresql://only:5432/db");
        assertThat(member.primaryConfig().maximumPoolSize).isEqualTo(10); // builder default
        assertThat(member.secondaryConfig()).isEmpty();
        assertThat(member.configFor("anything")).isEmpty();
    }

    @Test
    void should_throw_clear_error_when_member_omits_primary_config() {
        // GIVEN — secondary present but primary missing; validation in ShardMemberConfig's
        // private constructor requires "primaryConfig" key
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: broken
                    members:
                      - member: 0
                        configs:
                          secondaryConfig:
                            jdbcUrl: jdbc:postgresql://only-secondary:5432/db
                            username: app
                            password: secret
                """;

        // WHEN / THEN
        assertThatThrownBy(() -> MAPPER.readValue(yaml, ShardingConfig.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("primaryConfig");
    }

    @Test
    void should_fail_fast_on_unknown_property() {
        // GIVEN — typo: "memmbers" instead of "members"
        var yaml = """
                defaultShard: { group: 0, member: 0 }
                groups:
                  - group: 0
                    name: typo
                    memmbers:
                      - member: 0
                """;

        // WHEN / THEN — strict mode catches the misspelling
        assertThatThrownBy(() -> MAPPER.readValue(yaml, ShardingConfig.class))
                .isInstanceOf(MismatchedInputException.class)
                .hasMessageContaining("memmbers");
    }
}
