package io.ekbatan.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flat configuration for Ekbatan. Only {@code ekbatan.namespace} maps here; the
 * {@code ekbatan.sharding}, {@code ekbatan.jobs}, and {@code ekbatan.local-event-handler}
 * subtrees are bound separately by {@code EkbatanCoreConfiguration} via the Jackson hybrid path --
 * those config trees use builder-based POJOs (with private constructors + validation) that
 * Spring Boot's record binder cannot drive directly.
 *
 * @param namespace logical namespace recorded on every persisted event ({@code "default"} if unset).
 */
@ConfigurationProperties(prefix = "ekbatan")
public record EkbatanProperties(@DefaultValue("default") String namespace) {}
