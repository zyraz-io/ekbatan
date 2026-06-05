package io.ekbatan.di;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code Action} subclass for discovery by an Ekbatan DI integration
 * (Spring Boot, Quarkus, Micronaut). Discovered actions are registered as managed
 * beans and added to the framework's {@code ActionRegistry} so they can be invoked
 * via {@code ActionExecutor.execute(...)}.
 *
 * <p>This annotation is discovery-only. Execution behavior such as retries and
 * cross-shard allowance is configured with {@code ExecutionConfiguration} on the
 * {@code ActionExecutor} default or on a single {@code execute(...)} call.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EkbatanAction {}
