package io.ekbatan.spring.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static registry of {@code @EkbatanAction}-annotated classes discovered at AOT
 * processing time and made available to {@code EkbatanCoreConfiguration} at runtime
 * - a tiny shim that lets a Spring AOT-generated bean-factory initializer populate the
 * action list before the auto-config builds the {@code ActionRegistry}.
 *
 * <p>On JVM in non-AOT mode the holder stays empty and the auto-config falls back to
 * the runtime classpath scan. On JVM in AOT mode and on native image the
 * AOT-generated initializer (emitted by {@link EkbatanActionsAotProcessor}) populates
 * the holder before bean factory post-processing, and the auto-config reads from it
 * instead of attempting the (broken-on-native) runtime scan.
 *
 * <p>API kept minimal: a {@code Class<?>}-varargs setter so the generated code is
 * trivial - no generics in the codegen. The runtime cast back to
 * {@code Class<? extends Action<?, ?>>} is checked by the auto-config which already
 * validates the parent class.
 */
public final class EkbatanActionsHolder {

    private static final AtomicReference<Set<Class<?>>> ACTIONS = new AtomicReference<>(Collections.emptySet());

    private EkbatanActionsHolder() {}

    /**
     * Called by the AOT-generated initializer (or directly in tests) to populate the holder
     * with the discovered action classes.
     *
     * @param classes the {@code @EkbatanAction} classes discovered at AOT processing time.
     */
    public static void set(Class<?>... classes) {
        ACTIONS.set(Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(classes))));
    }

    /**
     * Takes the pending action classes, leaving the holder empty.
     *
     * <p>A handoff rather than a shared value, and that is the point: the field is
     * {@code static}, so it is one slot per JVM rather than one per application context, and
     * "is it empty?" is the only signal the auto-config has for deciding whether a list was
     * prepared for it. Left populated, a second context in the same JVM - routine in a Spring
     * test run, where contexts are cached and reused - read the first context's actions and
     * skipped its own classpath scan, so it registered someone else's actions and none of its
     * own. Emptying on read restores the fallback for every later context.
     *
     * <p>Each context's AOT initializer calls {@link #set} during its own refresh, immediately
     * before the auto-config reads, so the set/consume pairing holds per context. Not covered: a
     * context that calls {@code set} and then fails to refresh leaves its list behind, which
     * would need the list scoped to the context by construction - an AOT-registered bean
     * definition rather than a static - to solve properly.
     *
     * @return the AOT-discovered action classes, or an empty set if none.
     */
    public static Set<Class<?>> consume() {
        return ACTIONS.getAndSet(Collections.emptySet());
    }
}
