package io.ekbatan.spring.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Static registry of {@code @EkbatanAction}-annotated classes discovered at AOT
 * processing time and made available to {@code EkbatanCoreConfiguration} at runtime
 * — a tiny shim that lets a Spring AOT-generated bean-factory initializer populate the
 * action list before the auto-config builds the {@code ActionRegistry}.
 *
 * <p>On JVM in non-AOT mode the holder stays empty and the auto-config falls back to
 * the runtime classpath scan. On JVM in AOT mode and on native image the
 * AOT-generated initializer (emitted by {@link EkbatanActionsAotProcessor}) populates
 * the holder before bean factory post-processing, and the auto-config reads from it
 * instead of attempting the (broken-on-native) runtime scan.
 *
 * <p>API kept minimal: a {@code Class<?>}-varargs setter so the generated code is
 * trivial — no generics in the codegen. The runtime cast back to
 * {@code Class<? extends Action<?, ?>>} is checked by the auto-config which already
 * validates the parent class.
 */
public final class EkbatanActionsHolder {

    private static volatile Set<Class<?>> actions = Collections.emptySet();

    private EkbatanActionsHolder() {}

    /** Called by the AOT-generated initializer (or directly in tests). */
    public static void set(Class<?>... classes) {
        actions = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(classes)));
    }

    /** Returns the AOT-discovered action classes, or an empty set if none. */
    public static Set<Class<?>> get() {
        return actions;
    }
}
