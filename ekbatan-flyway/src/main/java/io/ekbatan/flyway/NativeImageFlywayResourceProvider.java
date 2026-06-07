package io.ekbatan.flyway;

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
 * file system to enumerate classpath migrations inside a native image.
 *
 * <p>Application code calls {@link FlywayMigrator}; the migrator installs this provider
 * automatically when the process is running as a native image. This class is intentionally
 * package-private so the public API stays centered on {@code FlywayMigrator}.
 */
final class NativeImageFlywayResourceProvider implements ResourceProvider {

    private final ClassLoader classLoader;
    private final Charset encoding;
    private final Location[] locations;

    /**
     * Constructs a provider bound to the Flyway configuration's effective locations and classloader.
     *
     * @param locations the Flyway {@link Location}s being scanned.
     * @param classLoader the classloader to read resources through.
     * @param encoding the migration-file character encoding.
     */
    NativeImageFlywayResourceProvider(Location[] locations, ClassLoader classLoader, Charset encoding) {
        this.locations = locations;
        this.classLoader = classLoader;
        this.encoding = encoding;
    }

    /** {@return true when the current process is running as a GraalVM native image} */
    static boolean inNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
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
                if (!"classpath:".equals(loc.getPrefix())) {
                    continue;
                }
                Path root = fs.getPath("/", loc.getRootPath());
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
