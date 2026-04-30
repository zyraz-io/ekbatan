package io.ekbatan.core.nativeimage;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.lang.reflect.Array;

/**
 * GraalVM substitution: replaces jOOQ's {@code Internal.arrayType(Class)} so it uses
 * {@code Array.newInstance(type, 0).getClass()} instead of {@code type.arrayType()}.
 *
 * <p>jOOQ's upstream method (in 3.20.x) does {@code if (true) return type.arrayType();}, which
 * under GraalVM native image returns {@code null} at runtime for some types whose array
 * reflection metadata isn't fully wired through the reachability metadata repo. The null
 * propagates into {@code DefaultDataType}'s static type-registration maps as a key, where
 * {@code ConcurrentHashMap.putIfAbsent} throws {@code NullPointerException}, leaving
 * {@code DefaultDSLContext} permanently uninitialized and every subsequent {@code DSL.using}
 * call failing with {@code NoClassDefFoundError}. The same jOOQ source has the alternative
 * {@code Array.newInstance(...).getClass()} as a commented-out fallback (after {@code else} of
 * the dead {@code if (true)}); this substitution simply elects that path under native image.
 *
 * <p>This is a JVM-invisible class — under HotSpot the SVM annotations are absent (svm.jar is
 * compileOnly) and the class loads but is never referenced. Under native image the
 * {@code @TargetClass}/@{@code Substitute} pair causes the substitution to take effect.
 *
 * <p>Partner fix: {@code resources/META-INF/native-image/io.ekbatan/ekbatan-core/
 * reachability-metadata.json} unconditionally registers the array types (Byte[], UShort[], etc.)
 * that this method now allocates. The upstream metadata in
 * {@code oracle/graalvm-reachability-metadata} gates them behind a CI-only test class
 * ({@code org_jooq.jooq.JooqTest}), so they never activate in real consumers.
 *
 * <p>See jOOQ#13658 (closed, R: Duplicate) for the upstream issue.
 */
@TargetClass(className = "org.jooq.impl.Internal")
final class Target_org_jooq_impl_Internal {

    @SuppressWarnings("unchecked")
    @Substitute
    public static <T> Class<T[]> arrayType(Class<T> type) {
        return (Class<T[]>) Array.newInstance(type, 0).getClass();
    }
}
