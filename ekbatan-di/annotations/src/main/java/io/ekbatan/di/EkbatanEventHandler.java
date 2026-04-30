package io.ekbatan.di;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code EventHandler} implementation for discovery by an Ekbatan DI integration
 * (Spring Boot, Quarkus, Micronaut). Discovered handlers are registered as managed beans and
 * added to the framework's {@code EventHandlerRegistry}, where the local-event-handler
 * fanout/handling jobs can route events to them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EkbatanEventHandler {}
