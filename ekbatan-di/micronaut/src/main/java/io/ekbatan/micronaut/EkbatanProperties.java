package io.ekbatan.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Typed configuration for Ekbatan. Only {@code ekbatan.namespace} maps here; the
 * {@code ekbatan.sharding}, {@code ekbatan.jobs}, and {@code ekbatan.local-event-handler}
 * subtrees are bound separately by {@link EkbatanCoreConfiguration} via the Jackson hybrid path —
 * those config trees use builder-based POJOs (with private constructors + validation) that
 * Micronaut's {@code @ConfigurationProperties} setter injection cannot drive directly.
 */
@ConfigurationProperties("ekbatan")
public final class EkbatanProperties {

    /** Required by Micronaut; the container instantiates this {@code @ConfigurationProperties} class and populates it via the generated setter. */
    public EkbatanProperties() {}

    private String namespace = "default";

    /** {@return logical namespace recorded on every persisted event (defaults to {@code "default"})} */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Sets the namespace.
     *
     * @param namespace logical namespace recorded on every persisted event.
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }
}
