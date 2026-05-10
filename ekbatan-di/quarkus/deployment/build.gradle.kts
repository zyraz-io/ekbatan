plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-quarkus-deployment")
    description.set("Quarkus extension deployment processor (build-time) for Ekbatan.")
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

dependencies {
    api(project(":ekbatan-di-quarkus-runtime"))

    api("io.quarkus:quarkus-core-deployment:${project.property("quarkusVersion")}")
    api("io.quarkus:quarkus-arc-deployment:${project.property("quarkusVersion")}")

    // Optional Ekbatan modules are deliberately NOT declared — the deployment processor only
    // references them via QuarkusClassLoader.isClassPresentAtRuntime, so adding them here would
    // load EventHandler/DistributedJob on the deployment classloader alongside the runtime one
    // and trip IncompatibleClassChangeError at @All List<...> injection.
}
