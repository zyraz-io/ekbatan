package io.ekbatan.core.test.testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.testcontainers.images.builder.Transferable;

/**
 * Reads a classpath resource into a Testcontainers {@link Transferable} so it can be
 * mounted into a container via {@code .withCopyToContainer(transferable, "/path")}.
 *
 * <p>Use instead of {@code MountableFile.forClasspathResource(...)}: that helper resolves
 * the resource to a real filesystem URL so Docker can bind-mount it, which fails under
 * GraalVM native image because classpath resources live in the image heap (resource://)
 * rather than on disk. {@code Transferable.of(byte[])} ships the bytes in-band over the
 * Docker API, working identically on JVM and native.
 */
public final class ClasspathTransferable {

    private ClasspathTransferable() {}

    /** Reads the resource and returns it as a {@link Transferable} for {@code withCopyToContainer}. */
    public static Transferable of(String resourceName) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream is = cl.getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found on classpath: " + resourceName);
            }
            return Transferable.of(is.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
