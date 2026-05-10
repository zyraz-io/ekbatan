plugins {
    `java-library`
    id("ekbatan.publishing")
    id("io.quarkus.extension") version "3.34.6"
    id("org.kordamp.gradle.jandex") version "2.3.0"
}

ekbatanPublishing {
    artifactId.set("ekbatan-quarkus")
    description.set("Quarkus extension runtime for Ekbatan — single-dep entry point for Quarkus apps.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// Quarkus's extension annotation processor generates AsciiDoc config-reference docs (a feature
// for the Quarkus website's docs build). Without a Maven pom.xml it can't detect the GAV and
// emits "We could not detect the groupId and artifactId of this module" on every build.
// We don't publish to the Quarkus website, so disable the config-doc generator explicitly.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-AgenerateDoc=false")
}

// kordamp's `jandex` task writes META-INF/jandex.idx into build/resources/main, the same dir
// processResources populates and that checkstyleMain reads. Gradle 9.x's task-validator refuses
// the implicit ordering — declare it explicitly.
tasks.named("checkstyleMain") {
    mustRunAfter("jandex")
}
// Same dependency-validator issue for the javadoc task (reads the same resources dir).
tasks.named("javadoc") {
    mustRunAfter("jandex")
}

quarkusExtension {
    // Under flat project paths, the plugin's default "sibling project named `deployment`"
    // lookup doesn't apply — the deployment project lives at a top-level path. Tell the
    // plugin both the project path and the Maven coordinate explicitly.
    deploymentModule.set(":ekbatan-di-quarkus-deployment")
    deploymentArtifact.set("${project.group}:ekbatan-di-quarkus-deployment:${project.version}")
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di-annotations"))

    // Must stay `api`: Quarkus's split classloader pins extension-runtime types to the parent
    // ('app') loader, but user-app deps land on the local QuarkusClassLoader. If these are
    // `compileOnly` here, the extension's EkbatanLocalEventHandlerConfiguration still imports
    // `EventHandler`/`EventHandlerRegistry`, the JVM resolves them via the parent loader, while
    // the user's `@EkbatanEventHandler` bean (e.g. via :di:shared) loads on QuarkusClassLoader
    // — two copies, ClassCastException at `@All List<EventHandler<?>>` injection. The
    // `isClassPresentAtRuntime` checks in EkbatanProcessor remain as a guard for users who
    // explicitly exclude these modules from their Quarkus app classpath.
    api(project(":ekbatan-events-local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    api("io.quarkus:quarkus-core:${project.property("quarkusVersion")}")
    api("io.quarkus:quarkus-arc:${project.property("quarkusVersion")}")

    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
}
