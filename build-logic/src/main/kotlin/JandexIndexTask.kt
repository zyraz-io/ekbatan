import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.jboss.jandex.Indexer
import org.jboss.jandex.IndexWriter
import java.nio.file.Files

/**
 * Generates a Jandex bean-archive index from compiled class files and writes it to
 * `<resourcesDir>/META-INF/jandex.idx`, where standard `processResources` / `jar`
 * picks it up and packages it into the final JAR.
 *
 * <h2>Why this exists</h2>
 * Downstream Quarkus apps that consume ekbatan-* runtime modules rely on these classes
 * being indexed for CDI bean discovery. Quarkus 3.34's on-the-fly Jandex indexer has a
 * bug in [Types.buildResolvedMap][1] that crashes with `IndexOutOfBoundsException` when a
 * `@Produces` method's type-closure walk hits a class with deeply nested wildcard
 * generics (we hit this with `@Produces EventHandlerRegistry ekbatanEventHandlerRegistry(
 * @All List<EventHandler<E extends ModelEvent<?>>> handlers)`). Pre-shipping a
 * `META-INF/jandex.idx` inside each ekbatan-* JAR makes Quarkus use the pre-built index
 * instead of indexing on the fly, sidestepping the bug.
 *
 * <h2>Why a custom task instead of the kordamp plugin</h2>
 * The `org.kordamp.gradle.jandex` plugin (1.1.0 through 2.3.0) is incompatible with
 * Gradle 9.x when applied to more than one subproject — its Guice-injected `Banner`
 * class hits a `ClassCastException` across Gradle's per-subproject classloaders. We
 * invoke the Jandex library directly to avoid the plugin entirely.
 *
 * <h2>Index format pinning</h2>
 * The Jandex library version is pinned to 3.1.8 in build-logic's dependencies; that
 * line produces format version 11, which is the highest Quarkus 3.x's `IndexReader`
 * accepts. Jandex 3.2+ would default to v12 and Quarkus 3.x would reject it (see
 * Quarkus discussion #41492).
 *
 * [1]: https://github.com/quarkusio/quarkus/blob/3.34.6/independent-projects/arc/processor/src/main/java/io/quarkus/arc/processor/Types.java
 */
abstract class JandexIndexTask : DefaultTask() {

    // `@InputFiles` + `@SkipWhenEmpty` (instead of `@InputDirectory`) so the task
    // gracefully no-ops on modules with no compiled classes (e.g. `ekbatan-spring-boot-starter`,
    // which is a bridge starter with no Java sources). `@InputDirectory` would fail
    // configuration-time validation when the dir hasn't been created yet.
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resourcesDir: DirectoryProperty

    @TaskAction
    fun generateIndex() {
        val classes = classesDir.get().asFile
        if (!classes.exists()) {
            logger.info("Classes directory does not exist yet; skipping Jandex indexing.")
            return
        }

        val indexer = Indexer()
        var indexed = 0
        var errors = 0
        classes.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { f ->
                runCatching {
                    f.inputStream().use(indexer::index)
                    indexed++
                }.onFailure {
                    errors++
                    logger.warn("Failed to index class file ${f.relativeTo(classes)}: ${it.message}")
                }
            }

        val index = indexer.complete()
        val outDir = resourcesDir.get().asFile.toPath().resolve("META-INF")
        Files.createDirectories(outDir)
        val outFile = outDir.resolve("jandex.idx")
        outFile.toFile().outputStream().buffered().use { out ->
            IndexWriter(out).write(index)
        }

        logger.lifecycle(
            "Jandex: indexed $indexed class files" +
                (if (errors > 0) " ($errors errors)" else "") +
                " → ${outFile.toAbsolutePath()}",
        )
    }
}
