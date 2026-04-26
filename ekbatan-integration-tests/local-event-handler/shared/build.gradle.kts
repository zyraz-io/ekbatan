plugins {
    id("java")
    id("java-test-fixtures")
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
    testFixturesApi(project(":ekbatan-core"))
    testFixturesApi(project(":ekbatan-distributed-jobs"))
    testFixturesApi(project(":ekbatan-events:local-event-handler"))

    testFixturesCompileOnly(project(":ekbatan-annotation-processor"))
    testFixturesAnnotationProcessor(project(":ekbatan-annotation-processor"))

    testFixturesApi("org.flywaydb:flyway-core:${project.property("flywayVersion")}")

    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testFixturesApi(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testFixturesApi("org.junit.jupiter:junit-jupiter:${project.property("junitJupiterVersion")}")
    testFixturesApi("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testFixturesRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testFixturesApi("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")

    testFixturesApi("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testFixturesApi("org.awaitility:awaitility:${project.property("awaitilityVersion")}")
    testFixturesApi("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
