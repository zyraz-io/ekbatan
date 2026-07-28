package io.ekbatan.quarkus.runtime;

import io.ekbatan.core.config.PropertyKeyNormalizer;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.Priorities;
import jakarta.annotation.Priority;
import java.io.Serial;

/**
 * Canonicalises {@code ekbatan.*} property NAMES so both the kebab-case and the camelCase spelling
 * of a key resolve to the same value for every consumer of SmallRye Config - not just Ekbatan's own
 * binder.
 *
 * <p>Ekbatan's binder ({@code EkbatanCoreConfiguration.collectSubtree}) already folds kebab to
 * camel via {@link PropertyKeyNormalizer} while ITERATING names, so both spellings bind. But
 * Quarkus's {@code @IfBuildProperty} matches its {@code name} as a literal string LOOKUP, so a
 * user who writes {@code ekbatan.localEventHandler.handling.enabled} got a bound config reporting
 * {@code handling.enabled == true} while the gated bean was never produced. This interceptor
 * closes that gap at the lookup level, which is where the gate lives.
 *
 * <p>Registered through {@code META-INF/services/io.smallrye.config.ConfigSourceInterceptor}.
 * SmallRye discovers it via {@link java.util.ServiceLoader}, and Quarkus builds BOTH its
 * build-time config ({@code BuildTimeConfigurationReader.initConfiguration} ->
 * {@code ConfigUtils.configBuilder()}) and its runtime config through
 * {@code ConfigUtils.emptyConfigBuilder()}, which calls {@code addDiscoveredInterceptors()}. So
 * the same rewriting applies at jar-assembly time (where {@code @IfBuildProperty} is evaluated)
 * and at runtime.
 *
 * <p>Priority matches {@code FallbackConfigSourceInterceptor} ({@code LIBRARY + 600}), placing this
 * outside the profile / expression / relocate interceptors so an aliased lookup still gets profile
 * resolution and expression expansion applied on the way in.
 *
 * <p>Only names under {@code ekbatan.} are considered, and only after the name as written failed to
 * resolve - so this can never change the meaning of an existing, explicitly-set property, and never
 * touches {@code quarkus.*} or application keys.
 *
 * <p>All spellings are covered, not just the two extremes. A key with N hyphens has 2^N accepted
 * spellings ({@code local-event-handler}, {@code localEventHandler}, {@code local-eventHandler},
 * {@code localEvent-handler}), so rather than enumerate candidates this folds every name to its
 * fully-camelCase form and matches on that.
 */
@Priority(Priorities.LIBRARY + 600)
public final class EkbatanPropertyNameInterceptor implements ConfigSourceInterceptor {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Only Ekbatan's own namespace is canonicalised; every other key passes through untouched. */
    private static final String PREFIX = "ekbatan.";

    /** Required by {@link java.util.ServiceLoader}. */
    public EkbatanPropertyNameInterceptor() {}

    /**
     * Resolves {@code name} as written first; only if that yields nothing does it retry under the
     * alternate spelling(s) of the same key.
     *
     * @param context the interceptor chain context.
     * @param name the configuration name being looked up.
     * @return the resolved value, reported under the name the caller asked for, or {@code null}.
     */
    @Override
    public ConfigValue getValue(final ConfigSourceInterceptorContext context, final String name) {
        final ConfigValue value = context.proceed(name);
        if (value != null || name == null || !name.startsWith(PREFIX)) {
            return value;
        }
        // Fold to the fully-camelCase form. Every accepted spelling of a key folds to the same
        // string, so this is the key's identity regardless of how it happens to be written.
        final String canonical = PropertyKeyNormalizer.kebabToCamel(name);

        // Cheap path: the source holds the fully-camelCase spelling.
        final ConfigValue camel = proceedIfDifferent(context, name, canonical);
        if (camel != null) {
            return camel;
        }

        // General path. A key with N hyphens has 2^N accepted spellings - `local-event-handler` can
        // also be written `localEventHandler`, `local-eventHandler` or `localEvent-handler` - so
        // enumerating candidates would be exponential and still incomplete. Instead, scan the names
        // that actually exist and take the one whose canonical form matches. Only reached when an
        // `ekbatan.` lookup already missed, which is rare and bounded by the size of the config.
        for (final var names = context.iterateNames(); names.hasNext(); ) {
            final String candidate = names.next();
            if (candidate != null
                    && candidate.startsWith(PREFIX)
                    && PropertyKeyNormalizer.kebabToCamel(candidate).equals(canonical)) {
                final ConfigValue hit = proceedIfDifferent(context, name, candidate);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * Looks {@code alias} up through the rest of the chain, reporting any hit under {@code name} so
     * callers (and error messages) still see the name they asked for.
     */
    private static ConfigValue proceedIfDifferent(
            final ConfigSourceInterceptorContext context, final String name, final String alias) {
        if (alias.equals(name)) {
            return null;
        }
        // proceed(), not restart(): this walks strictly INWARD down the remaining chain, so an
        // alias lookup can never re-enter this interceptor and recurse.
        final ConfigValue value = context.proceed(alias);
        return value != null ? value.withName(name) : null;
    }
}
