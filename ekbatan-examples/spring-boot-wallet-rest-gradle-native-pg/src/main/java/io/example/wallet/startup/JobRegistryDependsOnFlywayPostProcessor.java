package io.example.wallet.startup;

import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds {@code flywayInitializer} (Spring Boot's auto-configured migration runner) as a
 * {@code dependsOn} edge of the framework-defined {@code ekbatanJobRegistry} bean. Without
 * this, db-scheduler can start polling {@code scheduled_tasks} before Flyway has created
 * the table — racy on cold starts.
 *
 * <p>Implemented as a {@link BeanFactoryPostProcessor} because:
 * <ol>
 *   <li>The {@code ekbatanJobRegistry} bean definition is contributed by
 *       {@code ekbatan-spring-boot-starter} — we don't own its source, so we can't put
 *       {@code @DependsOn} on it directly.</li>
 *   <li>Mutating the bean's definition during the BFPP phase happens before any
 *       instantiation, so Spring's dependency resolver sees the edge.</li>
 * </ol>
 *
 * <p>The {@code @Bean} method is {@code static} because BFPP beans must be registered very
 * early — earlier than the configuration class's own bean post-processing. A non-static
 * factory method would force Spring to instantiate the @Configuration class first, which it
 * can't do safely from inside the BFPP phase.
 */
@Configuration
public class JobRegistryDependsOnFlywayPostProcessor {

    @Bean
    public static BeanFactoryPostProcessor jobRegistryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("ekbatanJobRegistry")) {
                return;
            }
            var bd = beanFactory.getBeanDefinition("ekbatanJobRegistry");
            var existing = bd.getDependsOn();
            var merged = existing == null
                    ? new String[] {"flywayInitializer"}
                    : Stream.concat(Arrays.stream(existing), Stream.of("flywayInitializer"))
                            .toArray(String[]::new);
            bd.setDependsOn(merged);
        };
    }
}
