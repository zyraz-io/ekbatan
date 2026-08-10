plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// The SMT fat JAR, mounted into the Debezium container as a Connect plugin.
val smtPluginJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

// The ActionEvent descriptor the SMT loads at connector startup.
val actionEventDescriptorFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    smtPluginJar(project(":ekbatan-events-streaming-debezium-smt-protobuf")) {
        isTransitive = false
    }
    actionEventDescriptorFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-protobuf",
                "configuration" to "actionEventDescriptor",
            ),
        ),
    )

    // The real outbox writer. EventEntityRepository binds eventlog.events with dynamic jOOQ
    // (DSL.name) and carries the dialect-specific converters under test, so no generated jOOQ
    // classes - and therefore no per-dialect codegen module - are needed here.
    testImplementation(project(":ekbatan-core"))

    // Decode what came out of Kafka with the published generated class, not a stand-in.
    testImplementation(project(":ekbatan-events-streaming-action-event-protobuf"))
    testImplementation("com.google.protobuf:protobuf-java:${project.property("protobufVersion")}")

    testImplementation("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    testImplementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testImplementation("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")
    testImplementation("org.apache.kafka:kafka-clients:${project.property("kafkaClientsVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-mysql:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-mariadb:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-kafka:${project.property("testcontainersVersion")}")

    // See debezium-kafka-json/build.gradle.kts for the exclusion rationale.
    testImplementation("io.debezium:debezium-testing-testcontainers:${project.property("debeziumVersion")}") {
        exclude(group = "io.quarkus")
        exclude(group = "io.quarkus.gizmo")
        exclude(group = "org.jboss.logmanager")
    }
    testImplementation("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(smtPluginJar, actionEventDescriptorFile)
    // Declared as inputs, not just dependencies: the SMT is mounted into the Debezium container
    // as a file rather than resolved on the test classpath, so without this Gradle considers the
    // task up to date after the transform changes and silently skips the whole pipeline.
    inputs.files(smtPluginJar, actionEventDescriptorFile)
    doFirst {
        systemProperty("smt.plugin.jar", smtPluginJar.singleFile.absolutePath)
        systemProperty("smt.action.event.descriptor", actionEventDescriptorFile.singleFile.absolutePath)
    }
}

// `nativeTest` is a NativeRunTask, not a Test task, so the `systemProperty` calls above do not
// reach it. Without this mirror the test classes' static initialisers read a null property and
// die in `<clinit>` with `Path.of(null)` - reported as ExceptionInInitializerError followed by
// NoClassDefFoundError on every test in the class, which reads like a native-image reflection
// problem rather than a missing -D. The avro and protobuf sibling modules already carry this.
extensions.configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
    binaries.named("test") {
        runtimeArgs.addAll(
            provider {
                listOf(
                    "-Dsmt.plugin.jar=${smtPluginJar.singleFile.absolutePath}",
                    "-Dsmt.action.event.descriptor=${actionEventDescriptorFile.singleFile.absolutePath}",
                )
            },
        )
    }
}

tasks.named("nativeTest") { dependsOn(smtPluginJar, actionEventDescriptorFile) }
