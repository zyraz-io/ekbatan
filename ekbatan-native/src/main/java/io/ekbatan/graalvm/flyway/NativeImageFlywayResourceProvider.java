package io.ekbatan.graalvm.flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resource.classpath.ClassPathResource;

/**
 * Flyway {@link ResourceProvider} that walks GraalVM Substrate's {@code resource:/} NIO
 * file system to enumerate migrations on native image — needed because Flyway's default
 * {@code ClassPathScanner} relies on {@code ClassLoader.getResources(dir)} returning a
 * {@code file:} or {@code jar:} URL, which native image cannot satisfy.
 *
 * <p>Use this directly via {@code Flyway.configure().resourceProvider(...)} when running
 * on native image, or call {@link FlywayHelper#migrate(String, String, String, String...)}
 * which wires it conditionally based on {@link #inNativeImage()}.
 *
 * <p>Same approach Flyway PR <a href="https://github.com/flyway/flyway/pull/3846">#3846</a>
 * proposed (still unmerged as of 2026-05) and Spring Boot 4 ships internally as
 * {@code NativeImageResourceProvider}.
 */
public final class NativeImageFlywayResourceProvider implements ResourceProvider {

    private final ClassLoader classLoader;
    private final Charset encoding;
    private final Location[] locations;

    public NativeImageFlywayResourceProvider(Location[] locations, ClassLoader classLoader, Charset encoding) {
        this.locations = locations;
        this.classLoader = classLoader;
        this.encoding = encoding;
    }

    /** Returns true when the current process is running as a GraalVM native image. */
    public static boolean inNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    @Override
    public LoadableResource getResource(String name) {
        return classLoader.getResource(name) == null ? null : new ClassPathResource(null, name, classLoader, encoding);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("resource:/"), Map.of())) {
            List<LoadableResource> out = new ArrayList<>();
            for (Location loc : locations) {
                if (!loc.isClassPath()) {
                    continue;
                }
                Path root = fs.getPath("/", loc.getPath());
                if (!Files.isDirectory(root)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().startsWith(prefix))
                            .filter(p -> Arrays.stream(suffixes)
                                    .anyMatch(s -> p.getFileName().toString().endsWith(s)))
                            .map(p -> new ClassPathResource(
                                    null, fs.getPath("/").relativize(p).toString(), classLoader, encoding))
                            .forEach(out::add);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
