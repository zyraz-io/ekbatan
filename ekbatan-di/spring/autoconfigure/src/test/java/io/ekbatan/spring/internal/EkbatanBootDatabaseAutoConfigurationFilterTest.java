package io.ekbatan.spring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.spring.EkbatanCoreConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EkbatanBootDatabaseAutoConfigurationFilterTest {

    private static final String EKBATAN_CORE = EkbatanCoreConfiguration.class.getName();
    private static final String BOOT4_DATASOURCE =
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration";
    private static final String BOOT4_FLYWAY = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";
    private static final String BOOT3_DATASOURCE =
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration";
    private static final String BOOT3_FLYWAY = "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration";
    private static final String OTHER = "com.example.OtherAutoConfiguration";

    private final EkbatanBootDatabaseAutoConfigurationFilter filter = new EkbatanBootDatabaseAutoConfigurationFilter();

    @Test
    void excludesBootDatabaseAutoConfigurationWhenEkbatanCoreIsImported() {
        var matches = filter.match(
                new String[] {EKBATAN_CORE, BOOT4_DATASOURCE, BOOT4_FLYWAY, BOOT3_DATASOURCE, BOOT3_FLYWAY, OTHER, null
                },
                null);

        assertThat(matches).containsExactly(true, false, false, false, false, true, true);
    }

    @Test
    void leavesBootDatabaseAutoConfigurationAloneWhenEkbatanCoreIsNotImported() {
        var matches = filter.match(new String[] {BOOT4_DATASOURCE, BOOT4_FLYWAY, OTHER}, null);

        assertThat(matches).containsExactly(true, true, true);
    }

    @Test
    void leavesBootDatabaseAutoConfigurationAloneWhenEscapeHatchAllowsIt() {
        var filter = new EkbatanBootDatabaseAutoConfigurationFilter();
        filter.setEnvironment(
                new MockEnvironment().withProperty("ekbatan.spring.allow-boot-database-auto-configuration", "true"));

        var matches = filter.match(new String[] {EKBATAN_CORE, BOOT4_DATASOURCE, BOOT4_FLYWAY, OTHER}, null);

        assertThat(matches).containsExactly(true, true, true, true);
    }
}
