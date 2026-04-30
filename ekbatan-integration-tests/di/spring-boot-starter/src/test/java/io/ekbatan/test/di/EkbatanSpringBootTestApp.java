package io.ekbatan.test.di;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * {@code @SpringBootApplication} root for the integration test. Its package
 * ({@code io.ekbatan.test.di}) is what
 * {@link org.springframework.boot.autoconfigure.AutoConfigurationPackages} registers as the scan
 * base, which is also what Ekbatan's
 * {@link io.ekbatan.spring.internal.EkbatanStereotypeBeanRegistrar} consults when looking up
 * {@code @EkbatanRepository} / {@code @EkbatanEventHandler} beans, and what
 * {@code EkbatanCoreConfiguration#scanActionClasses} consults for {@code @EkbatanAction}.
 *
 * <p>Placed at {@code io.ekbatan.test.di} (the parent of {@code shared.widget.*}) rather than
 * {@code io.ekbatan.test.di.spring_boot_starter} so the auto-config scan reaches the shared
 * module's widget classes. {@code scanBasePackages} on {@code @SpringBootApplication} only
 * widens {@code @ComponentScan} — it does <em>not</em> change
 * {@code AutoConfigurationPackages}'s registered base, so just placing the class here is the
 * cleanest way to widen the Ekbatan scan.
 */
@SpringBootApplication
public class EkbatanSpringBootTestApp {}
