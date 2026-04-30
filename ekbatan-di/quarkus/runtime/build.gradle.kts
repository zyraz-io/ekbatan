plugins {
    `java-library`
    id("io.quarkus.extension") version "3.34.6"
    id("org.kordamp.gradle.jandex") version "2.3.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// kordamp's `jandex` task writes META-INF/jandex.idx into build/resources/main, the same dir
// processResources populates and that checkstyleMain reads. Gradle 9.x's task-validator refuses
// the implicit ordering — declare it explicitly.
tasks.named("checkstyleMain") {
    mustRunAfter("jandex")
}

quarkusExtension {
    // Project name is `runtime` (path's last segment), so the plugin's default
    // `<group>:<parent-name>-deployment` would resolve to `io.ekbatan:quarkus-deployment` —
    // non-unique. Override to the real public coordinate.
    deploymentArtifact.set("${project.group}:ekbatan-di-quarkus-deployment:${project.version}")
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di:bootstrap"))
    api(project(":ekbatan-di:annotations"))
    api(project(":ekbatan-events:local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    api("io.quarkus:quarkus-core:${project.property("quarkusVersion")}")
    api("io.quarkus:quarkus-arc:${project.property("quarkusVersion")}")

    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
}
