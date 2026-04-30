plugins {
    id("java")
    id("io.micronaut.library") version "4.6.1"
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

micronaut {
    version(project.property("micronautVersion") as String)
}

dependencies {
    // Widget domain + jOOQ-generated tables + Flyway migrations + Ekbatan core/events/jobs.
    implementation(project(":ekbatan-integration-tests:di:shared"))
    implementation(project(":ekbatan-di:micronaut"))

    // Same jar on the AP path: the EkbatanStereotypeVisitor needs to be visible to
    // micronaut-inject-java when the di:shared module's @EkbatanAction classes are compiled.
    // Without this, the user-side annotations don't lift to @Singleton and Micronaut treats the
    // classes as plain non-beans.
    annotationProcessor(project(":ekbatan-di:micronaut"))

    // Test
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation(testFixtures(project(":ekbatan-core")))
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.awaitility:awaitility:${project.property("awaitilityVersion")}")

    // application.yml — Micronaut's runtime classpath check requires snakeyaml when any *.yml is
    // shipped (the io.micronaut.library plugin's `inspectRuntimeClasspath` task verifies this).
    runtimeOnly("org.yaml:snakeyaml")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
