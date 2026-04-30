package io.ekbatan.graalvm.testcontainers;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ScanResult;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Native Image {@link Feature} that registers Testcontainers' shaded
 * docker-java types AND the non-shaded {@code com.github.dockerjava.*} types
 * (transitively pulled in by Testcontainers extensions like
 * {@code debezium-testing-testcontainers}) for full reflection.
 *
 * <p><b>Why</b>: docker-java is a Jackson 2 POJO library — its command builders, request
 * bodies, and response models are serialized / deserialized via reflective getter / setter
 * calls. The upstream Testcontainers RMR (latest metadata-version 1.19.8 in
 * {@code oracle/graalvm-reachability-metadata}) only registers
 * {@code allDeclaredFields: true} for the {@code *CmdImpl} classes — not methods — and
 * does not cover the non-shaded {@code com.github.dockerjava.api.*} response types at
 * all. That's enough whack-a-mole that scanning the whole package once and registering
 * every class is the only sane fix for a native integration test that uses Docker via
 * Testcontainers.
 *
 * <p><b>When does it run?</b> The Feature is auto-loaded for every native-image build
 * by virtue of its {@code META-INF/native-image/.../native-image.properties}, but it
 * <b>no-ops</b> when {@code org.testcontainers.DockerClientFactory} is not reachable —
 * i.e. for production native binaries that don't pull Testcontainers in. Native-image's
 * analyser then prunes this Feature's bytecode from the final image.
 *
 * <p><b>Scope override</b>: the default scan packages cover stock Testcontainers +
 * docker-java. Downstream tests with additional shaded variants can extend the list via
 * {@code -Dio.ekbatan.graalvm.testcontainers.scan.packages=com.github.dockerjava,my.shaded.dockerjava}
 * passed as a build argument.
 */
public final class TestcontainersDockerJavaFeature implements Feature {

    private static final String SCAN_PACKAGES_PROPERTY = "io.ekbatan.graalvm.testcontainers.scan.packages";

    /**
     * Restricted to packages where Jackson 2 actually walks docker-java types: the API
     * command interfaces, the API model (response) types, and the core CmdImpl classes.
     * Previously this scanned all of {@code com.github.dockerjava.*} and
     * {@code org.testcontainers.shaded.com.github.dockerjava.*} (~2000 classes including
     * {@code netty}, {@code transport.*} and other infra classes that Jackson never
     * touches). Trimming to these packages cuts the registered set by ~70%.
     */
    private static final String DEFAULT_SCAN_PACKAGES = "com.github.dockerjava.api.command,"
            + "com.github.dockerjava.api.model,"
            + "com.github.dockerjava.core.command,"
            + "org.testcontainers.shaded.com.github.dockerjava.api.command,"
            + "org.testcontainers.shaded.com.github.dockerjava.api.model,"
            + "org.testcontainers.shaded.com.github.dockerjava.core.command";

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // No-op when Testcontainers is not on the classpath. Production native builds
        // that don't pull Testcontainers see this Feature get pruned by the analyser.
        if (access.findClassByName("org.testcontainers.DockerClientFactory") == null) {
            return;
        }

        String[] scanRoots = System.getProperty(SCAN_PACKAGES_PROPERTY, DEFAULT_SCAN_PACKAGES)
                .split("\\s*,\\s*");
        String[] classpath = access.getApplicationClassPath().stream()
                .map(p -> p.toAbsolutePath().toString())
                .toArray(String[]::new);

        int registered = 0;
        try (ScanResult scan = new ClassGraph()
                .overrideClasspath((Object[]) classpath)
                .enableClassInfo()
                .ignoreClassVisibility()
                .acceptPackages(scanRoots)
                .scan()) {

            for (var ci : scan.getAllClasses()) {
                Class<?> cls;
                try {
                    cls = ci.loadClass();
                } catch (Throwable ignored) {
                    continue;
                }
                if (!registerForFullReflection(cls)) {
                    continue;
                }
                registered++;
            }
        }
        System.out.println("[ekbatan-native] TestcontainersDockerJavaFeature: registered " + registered
                + " docker-java classes for full reflection");
    }

    /**
     * Best-effort full-reflection registration. {@code getDeclaredConstructors} /
     * {@code getDeclaredMethods} can throw {@link NoClassDefFoundError} on classes whose
     * declared parameter / return types aren't loadable on the build classpath
     * (common in shaded jars whose optional deps aren't pulled in) — skip them, they
     * almost certainly aren't reachable from the application anyway.
     *
     * <p>Both bulk and per-element registration are required: on GraalVM 25,
     * {@code registerAllDeclared*} only allows the QUERY API (so
     * {@code Class.getDeclaredMethods()} returns them) — explicit
     * {@code RuntimeReflection.register(method)} adds the invocation path. Jackson 2
     * uses {@code Method.invoke} on docker-java getters during response deserialization,
     * which requires the per-element registration.
     */
    private static boolean registerForFullReflection(Class<?> cls) {
        try {
            RuntimeReflection.register(cls);
            RuntimeReflection.registerAllDeclaredFields(cls);
            RuntimeReflection.registerAllDeclaredMethods(cls);
            RuntimeReflection.registerAllDeclaredConstructors(cls);
            for (var ctor : cls.getDeclaredConstructors()) {
                RuntimeReflection.register(ctor);
            }
            for (var method : cls.getDeclaredMethods()) {
                RuntimeReflection.register(method);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
