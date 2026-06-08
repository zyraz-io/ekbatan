package io.ekbatan.spring.internal;

import io.ekbatan.spring.EkbatanCoreConfiguration;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Suppresses Spring Boot's single-datasource database auto-configuration when Ekbatan's Spring
 * auto-configuration is active. Ekbatan owns its own Hikari pools through {@code ekbatan.sharding.*};
 * letting Boot also create a default {@code DataSource} or run default Flyway migration introduces
 * confusing duplicate database ownership.
 */
public final class EkbatanBootDatabaseAutoConfigurationFilter
        implements AutoConfigurationImportFilter, EnvironmentAware {

    private static final String EKBATAN_CORE_CONFIGURATION = EkbatanCoreConfiguration.class.getName();

    private static final String ALLOW_BOOT_DATABASE_AUTO_CONFIGURATION_PROPERTY =
            "ekbatan.spring.allow-boot-database-auto-configuration";

    private static final Set<String> BOOT_DATABASE_AUTO_CONFIGURATIONS = Set.of(
            // Spring Boot 4 module names.
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            // Spring Boot 3 names, kept harmlessly for users trying the starter with older Boot.
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");

    private Environment environment;

    /** Required by SpringFactoriesLoader. */
    public EkbatanBootDatabaseAutoConfigurationFilter() {}

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
        var ekbatanCoreIsBeingImported = !bootDatabaseAutoConfigurationAllowed()
                && contains(autoConfigurationClasses, EKBATAN_CORE_CONFIGURATION);
        var matches = new boolean[autoConfigurationClasses.length];
        for (var i = 0; i < autoConfigurationClasses.length; i++) {
            var autoConfigurationClass = autoConfigurationClasses[i];
            matches[i] = !ekbatanCoreIsBeingImported
                    || autoConfigurationClass == null
                    || !BOOT_DATABASE_AUTO_CONFIGURATIONS.contains(autoConfigurationClass);
        }
        return matches;
    }

    private static boolean contains(String[] values, String candidate) {
        for (var value : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean bootDatabaseAutoConfigurationAllowed() {
        if (environment == null) {
            return false;
        }
        return environment.getProperty(ALLOW_BOOT_DATABASE_AUTO_CONFIGURATION_PROPERTY, Boolean.class, false);
    }
}
