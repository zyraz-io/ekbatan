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

    testImplementation("io.debezium:debezium-testing-testcontainers:${project.property("debeziumVersion")}")
    testImplementation("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
