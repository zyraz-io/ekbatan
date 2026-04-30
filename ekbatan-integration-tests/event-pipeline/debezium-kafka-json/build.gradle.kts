plugins {
    id("java")
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

dependencies {
    testImplementation(project(":ekbatan-integration-tests:event-pipeline:common"))

    testImplementation(testFixtures(project(":ekbatan-core")))
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-kafka:${project.property("testcontainersVersion")}")

    // The Debezium SMT plugin is shipped as a JVM Kafka Connect plugin and runs inside
    // its own Connect container. The integration test only needs the thin Testcontainers
    // wrapper (`DebeziumContainer` + `ConnectorConfiguration`) to launch / talk to that
    // container — NOT Debezium's runtime internals.
    //
    // `debezium-testing-testcontainers` pulls Quarkus's full test stack as a transitive
    // (it uses Quarkus internally for ITS own tests) plus jboss-logmanager which
    // installs a JBossLoggerFinder via System.LoggerFinder SPI. Both are unfit for the
    // native test image: jboss-logmanager ends up in the image heap, and Quarkus's
    // bootstrap/logging classes fail to resolve. Exclude both groups; the wrapper
    // classes themselves don't need them at runtime — we use SLF4J + slf4j-simple.
    testImplementation("io.debezium:debezium-testing-testcontainers:${project.property("debeziumVersion")}") {
        exclude(group = "io.quarkus")
        exclude(group = "io.quarkus.gizmo")
        exclude(group = "org.jboss.logmanager")
    }
    testImplementation("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
