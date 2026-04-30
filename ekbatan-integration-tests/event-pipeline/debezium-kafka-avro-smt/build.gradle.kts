plugins {
    id("java")
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

group = "io.ekbatan.test"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// Resolve the SMT fat JAR so the test can mount it into the Debezium container
val smtPluginJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// Resolve the ActionEvent.avsc file from the action-event:avro module for mounting into the container
val actionEventSchemaFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    smtPluginJar(project(":ekbatan-events:streaming:debezium-smt:avro")) {
        isTransitive = false
    }
    actionEventSchemaFile(
        project(
            mapOf("path" to ":ekbatan-events:streaming:action-event:avro", "configuration" to "actionEventSchema"),
        ),
    )

    testImplementation(project(":ekbatan-events:streaming:action-event:avro"))
    testImplementation(project(":ekbatan-integration-tests:event-pipeline:common"))

    // Avro for consumer-side deserialization
    testImplementation("org.apache.avro:avro:${project.property("avroVersion")}")

    testImplementation(testFixtures(project(":ekbatan-core")))
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-kafka:${project.property("testcontainersVersion")}")

    // See debezium-kafka-json/build.gradle.kts for the exclusion rationale: Debezium's
    // test wrapper transitively pulls Quarkus + jboss-logmanager which break the native
    // test image. The two classes our tests use don't need them at runtime.
    testImplementation("io.debezium:debezium-testing-testcontainers:${project.property("debeziumVersion")}") {
        exclude(group = "io.quarkus")
        exclude(group = "io.quarkus.gizmo")
        exclude(group = "org.jboss.logmanager")
    }
    testImplementation("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(smtPluginJar, actionEventSchemaFile)
    doFirst {
        systemProperty("smt.plugin.jar", smtPluginJar.singleFile.absolutePath)
        systemProperty(
            "smt.payload.schemas.dir",
            project.layout.projectDirectory
                .dir("src/test/avro")
                .asFile.absolutePath,
        )
        systemProperty("smt.action.event.schema", actionEventSchemaFile.singleFile.absolutePath)
    }
}

// `nativeTest` is a NativeRunTask, not a Test task, so the systemProperty(...) calls
// above don't reach it. Mirror the same -D values via runtimeArgs so the test class's
// static initialiser sees them when run as a native binary too.
extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
    binaries.named("test") {
        runtimeArgs.addAll(
            provider {
                listOf(
                    "-Dsmt.plugin.jar=${smtPluginJar.singleFile.absolutePath}",
                    "-Dsmt.payload.schemas.dir=${project.layout.projectDirectory.dir("src/test/avro").asFile.absolutePath}",
                    "-Dsmt.action.event.schema=${actionEventSchemaFile.singleFile.absolutePath}",
                )
            },
        )
    }
}
tasks.named("nativeTest") { dependsOn(smtPluginJar, actionEventSchemaFile) }
