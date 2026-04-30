package io.ekbatan.graalvm.jackson;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Native Image {@link Feature} that registers every Java {@code record} found
 * under the configured package roots for full reflective access — including
 * {@link Class#getRecordComponents() record components}, which Jackson 3
 * ({@code tools.jackson.databind}) requires to deserialize records.
 *
 * <p>Without this, every native-image build that uses Jackson 3 + records dies at runtime
 * with:
 * <pre>UnsupportedFeatureError: Record components not available for record class ...</pre>
 *
 * <h3>What it registers</h3>
 * <ul>
 *   <li>Every Java {@code record} found under the scan roots — full reflective access plus
 *       {@code RuntimeReflection.registerAllRecordComponents}.</li>
 *   <li>Every class with a nested {@code Builder} inner class — Jackson uses reflection to
 *       call {@code build()} and the property setters (covers both
 *       {@code @JsonDeserialize(builder = ...)} and the project's {@code @AutoBuilder}
 *       annotation processor).</li>
 *   <li>Every class with at least one {@code @JsonCreator}-annotated method (Jackson 2 or
 *       Jackson 3 package). The declaring class is fully registered (so Jackson can read its
 *       annotations — important for the project's mixin pattern), and if any such method is
 *       static its return type is also registered (so Jackson can invoke the real factory
 *       behind a mixin like {@code ShardIdentifierMixin.of() → ShardIdentifier}).</li>
 *   <li>Every class whose package name contains {@code .generated.jooq.} — jOOQ's codegen
 *       output uses reflection to instantiate Records via no-arg constructors.</li>
 * </ul>
 *
 * <h3>Configuring scan roots</h3>
 * Default scan root is {@code io.ekbatan}. Override at native-image build time with:
 * <pre>
 *     -Dio.ekbatan.graalvm.scan.packages=io.ekbatan,com.acme.myapp
 * </pre>
 * (set via {@code graalvmNative.binaries.named("main") { buildArgs.add("-D...") }} in
 * Gradle, or as a JVM system property when invoking {@code native-image} directly).
 *
 * <h3>Auto-loading</h3>
 * The Feature is registered automatically via
 * {@code META-INF/native-image/io.ekbatan/ekbatan-native/native-image.properties} which
 * appends {@code --features=io.ekbatan.graalvm.jackson.Jackson3RecordsFeature} to the
 * native-image command. Drop this dependency on the classpath and it activates.
 *
 * <h3>Why it exists</h3>
 * Jackson 3 ships zero native-image metadata
 * (<a href="https://github.com/oracle/graalvm-reachability-metadata/issues/697">RMR #697</a>
 * closed unresolved), and the modern reachability-metadata.json schema dropped
 * {@code allRecordComponents}, so the only working path is the programmatic
 * {@link RuntimeReflection#registerAllRecordComponents(Class)} API called from a Feature
 * during {@code beforeAnalysis} (calls from a reachability handler arrive too late).
 */
public final class Jackson3RecordsFeature implements Feature {

    private static final String SCAN_PACKAGES_PROPERTY = "io.ekbatan.graalvm.scan.packages";
    private static final String DEFAULT_SCAN_PACKAGES = "io.ekbatan";

    /**
     * Both Jackson 2 ({@code com.fasterxml.jackson.annotation.JsonCreator}) and Jackson 3
     * ({@code tools.jackson.annotation.JsonCreator}) — Jackson 3 still recognises the
     * Jackson 2 annotation package for backward compatibility, and the project mixes them.
     */
    private static final String[] JSON_CREATOR_ANNOTATIONS = {
        "com.fasterxml.jackson.annotation.JsonCreator", "tools.jackson.annotation.JsonCreator"
    };

    /**
     * Per-build dedup of class registration. Inheritance walks for sibling leaf classes
     * (e.g. concrete event records that all extend the same {@code ModelEvent} super) hit
     * the same superclass repeatedly — without this set we'd re-issue the same
     * {@code RuntimeReflection.register*} calls dozens of times per shared superclass.
     */
    private final java.util.Set<Class<?>> registered = new java.util.HashSet<>();

    /**
     * Tracked separately because record / Builder leaves call us with
     * {@code includeFields=false} (Jackson 3 reads records via canonical constructor +
     * record components, and Builders via setter methods — fields are unused). A later
     * call for the same class with {@code includeFields=true} must still register the
     * fields, even if {@link #registered} already contains the class.
     */
    private final java.util.Set<Class<?>> fieldsRegistered = new java.util.HashSet<>();

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        String[] scanRoots = resolveScanRoots();
        String[] classpath = access.getApplicationClassPath().stream()
                .map(p -> p.toAbsolutePath().toString())
                .toArray(String[]::new);

        int recordCount = 0;
        int builderCount = 0;
        int jooqRecordCount = 0;
        int creatorCount = 0;
        java.util.Set<String> creatorTargets = new java.util.HashSet<>();
        try (ScanResult scan = new ClassGraph()
                .overrideClasspath((Object[]) classpath)
                .enableClassInfo()
                .enableMethodInfo()
                .enableAnnotationInfo()
                // Mixin classes (e.g. ShardIdentifierMixin in EkbatanConfigJacksonModule)
                // are package-private inner classes — without this, ClassGraph skips them.
                .ignoreClassVisibility()
                // Several @JsonCreator-annotated constructors in the project are private
                // (e.g. WidgetCreatedEvent's deserialization ctor) — without this,
                // ClassGraph hides them and we miss the @JsonCreator marker.
                .ignoreMethodVisibility()
                .acceptPackages(scanRoots)
                .scan()) {

            for (var ci : scan.getAllClasses()) {
                Class<?> cls = tryLoad(ci);
                if (cls == null) continue;
                if (ci.isRecord()) {
                    // Records: Jackson 3 reads via canonical constructor + record
                    // components (accessor methods). Fields are unused — skip them. The
                    // inheritance chain is always Record → Object, neither of which
                    // contributes to deserialisation, so don't walk it.
                    register(cls, false, false);
                    RuntimeReflection.registerAllRecordComponents(cls);
                    recordCount++;
                } else if (ci.getPackageName().contains(".generated.jooq.")) {
                    // jOOQ generated POJOs/Records use field-level reflection in
                    // DefaultRecordUnmapper paths — keep fields registered. Walk the
                    // chain because some jOOQ generated types extend abstract bases.
                    register(cls, true, true);
                    jooqRecordCount++;
                }
                // Any nested `Builder` inner class — covers both Jackson's
                // `@JsonDeserialize(builder = ...)` and the project's `@AutoBuilder`
                // annotation processor pattern, which both produce `Outer$Builder` types
                // that Jackson 3 invokes reflectively to call `build()` and the setters.
                // Builders are flat (extend Object) and Jackson reads them via setter
                // methods, so skip both fields and the chain walk.
                for (Class<?> nested : cls.getDeclaredClasses()) {
                    if (nested.getSimpleName().equals("Builder")) {
                        register(nested, false, false);
                        builderCount++;
                    }
                }
            }

            // Classes with @JsonCreator-annotated methods. Catches:
            //   - Real classes that annotate their own factories (e.g. a value class
            //     with @JsonCreator on a static `of(...)` or constructor).
            //   - Mixin classes (the project's bootstrap pattern) whose @JsonCreator
            //     factory points at a real class — we register the mixin (annotations
            //     reachable for Jackson) AND the real return type (so Jackson can invoke
            //     the corresponding method on the real class).
            for (var ci : scan.getAllClasses()) {
                boolean classHasCreator = false;
                java.util.List<String> creatorReturnTypes = new java.util.ArrayList<>();
                // ClassGraph splits methods and constructors: getDeclaredMethodInfo()
                // returns instance/static methods only; getDeclaredConstructorInfo()
                // returns constructors. @JsonCreator can be on either, so check both.
                java.util.List<io.github.classgraph.MethodInfo> candidates = new java.util.ArrayList<>();
                candidates.addAll(ci.getDeclaredMethodInfo());
                candidates.addAll(ci.getDeclaredConstructorInfo());
                for (var mi : candidates) {
                    boolean methodHasCreator = false;
                    for (var ai : mi.getAnnotationInfo()) {
                        for (String wanted : JSON_CREATOR_ANNOTATIONS) {
                            if (wanted.equals(ai.getName())) {
                                methodHasCreator = true;
                                break;
                            }
                        }
                        if (methodHasCreator) break;
                    }
                    if (!methodHasCreator) continue;
                    classHasCreator = true;
                    // Static factory methods produce a real class (mixin pattern → real
                    // type). Constructors return the declaring class itself, which is
                    // already covered by the register(cls, ...) call below.
                    if (mi.isStatic() && !mi.isConstructor()) {
                        creatorReturnTypes.add(mi.getTypeSignatureOrTypeDescriptor()
                                .getResultType()
                                .toString());
                    }
                }
                if (!classHasCreator) continue;
                Class<?> cls = tryLoad(ci);
                if (cls == null) continue;
                // Conservative: classes with @JsonCreator may also have @JsonProperty
                // fields and arbitrary user-defined inheritance. Keep fields + walk.
                register(cls, true, true);
                creatorCount++;
                for (String rtName : creatorReturnTypes) {
                    if (!creatorTargets.add(rtName)) continue;
                    Class<?> rt = tryLoadByName(rtName);
                    if (rt != null && !rt.isPrimitive() && rt != void.class) {
                        register(rt, true, true);
                    }
                }
            }
        }
        System.out.println("[ekbatan-native] Jackson3RecordsFeature: registered " + recordCount + " records, "
                + jooqRecordCount + " jOOQ-generated classes, " + builderCount + " builders, " + creatorCount
                + " @JsonCreator-bearing classes (+ " + creatorTargets.size()
                + " creator-target return types) under " + String.join(",", scanRoots));
    }

    private static Class<?> tryLoadByName(String fqcn) {
        try {
            return Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] resolveScanRoots() {
        String value = System.getProperty(SCAN_PACKAGES_PROPERTY, DEFAULT_SCAN_PACKAGES);
        return value.split("\\s*,\\s*");
    }

    /**
     * Loads a class via ClassGraph but tolerates classes that can't be loaded — e.g. a
     * Micronaut {@code TypeElementVisitor} compile-time stub whose super-interface lives
     * in a build-time-only artifact. Skipping is safe: such classes aren't reachable at
     * native-image runtime anyway.
     */
    private static Class<?> tryLoad(io.github.classgraph.ClassInfo ci) {
        try {
            return ci.loadClass();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Registers a class for reflection.
     *
     * <p><b>Why both bulk and per-element registration?</b> On GraalVM 25,
     * {@code registerAllDeclared*} only registers methods/constructors for the QUERY API
     * (so {@code Class.getDeclaredMethods()} returns them) — it does NOT make them
     * invocable through {@code Method.invoke} / {@code MethodHandle}. The explicit
     * per-element {@code RuntimeReflection.register(method)} / {@code register(ctor)}
     * calls add the invocation-path metadata Jackson 3 needs (it uses MethodHandles for
     * record accessors). Without the per-element loop, native runtime fails with
     * {@code MissingReflectionRegistrationError: Cannot reflectively invoke ...}.
     *
     * @param cls            the class to register
     * @param includeFields  whether to register all declared fields. Records and Builders
     *                       pass {@code false} (Jackson reads them via record components /
     *                       setter methods, never field access).
     * @param walkChain      whether to walk up the inheritance chain to {@code Object}.
     *                       Records and Builders pass {@code false} (their chains are
     *                       trivial and contribute nothing).
     */
    private void register(Class<?> cls, boolean includeFields, boolean walkChain) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            boolean newClass = registered.add(c);
            boolean newFields = includeFields && fieldsRegistered.add(c);
            if (newClass) {
                RuntimeReflection.register(c);
                RuntimeReflection.registerAllDeclaredMethods(c);
                RuntimeReflection.registerAllDeclaredConstructors(c);
                for (var ctor : c.getDeclaredConstructors()) {
                    RuntimeReflection.register(ctor);
                }
                for (var method : c.getDeclaredMethods()) {
                    RuntimeReflection.register(method);
                }
            }
            if (newFields) {
                RuntimeReflection.registerAllDeclaredFields(c);
            }
            if (!walkChain) {
                return;
            }
            // If neither methods/ctors nor fields were newly registered for this class,
            // the entire superclass chain above it is also already covered (by induction
            // from a previous walk that put `c` in `registered`).
            if (!newClass && !newFields) {
                return;
            }
            c = c.getSuperclass();
        }
    }
}
