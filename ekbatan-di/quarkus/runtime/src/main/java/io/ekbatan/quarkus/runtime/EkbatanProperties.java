package io.ekbatan.quarkus.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Flat runtime configuration for Ekbatan. Only {@code ekbatan.namespace} maps here; the
 * {@code ekbatan.sharding}, {@code ekbatan.jobs}, and {@code ekbatan.local-event-handler}
 * subtrees are bound separately by {@link EkbatanCoreConfiguration} via the Jackson hybrid path
 * — those config trees use builder-based POJOs (with private constructors + validation) that
 * SmallRye {@code @ConfigMapping} cannot drive directly.
 */
@ConfigMapping(prefix = "ekbatan")
public interface EkbatanProperties {

    /**
     * Logical namespace recorded on every persisted event. Lets multiple deployments share an
     * eventlog table without their consumers stepping on each other's events.
     *
     * @return the configured namespace, or {@code "default"} if unset.
     */
    @WithDefault("default")
    String namespace();
}
