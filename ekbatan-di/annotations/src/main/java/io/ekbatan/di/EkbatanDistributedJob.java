package io.ekbatan.di;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code DistributedJob} implementation for discovery by an Ekbatan DI integration
 * (Spring Boot, Quarkus, Micronaut). Discovered jobs are registered as managed beans and added
 * to the framework's {@code JobRegistry}, which schedules them via db-scheduler at startup.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EkbatanDistributedJob {}
