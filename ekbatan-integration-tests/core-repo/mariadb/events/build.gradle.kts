plugins {
    id("java")
}

group = "io.ekbatan.core.test"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":ekbatan-core"))

    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation(testFixtures(project(":ekbatan-integration-tests:core-repo:shared")))
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-mariadb:${project.property("testcontainersVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
