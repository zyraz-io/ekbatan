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
    testImplementation(project(":ekbatan-core"))
    testImplementation(project(":ekbatan-distributed-jobs"))
    testImplementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    testImplementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    testImplementation("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
