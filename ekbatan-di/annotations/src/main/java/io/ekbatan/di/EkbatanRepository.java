package io.ekbatan.di;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@code AbstractRepository} subclass for discovery by an Ekbatan DI integration
 * (Spring Boot, Quarkus, Micronaut). Discovered repositories are registered as managed
 * beans and added to the framework's {@code RepositoryRegistry} keyed by their domain class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EkbatanRepository {}
