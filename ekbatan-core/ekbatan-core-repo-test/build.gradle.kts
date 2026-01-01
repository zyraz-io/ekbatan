import dev.monosoul.jooq.RecommendedVersions
import org.jooq.meta.jaxb.ForcedType


plugins {
    id("java")
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

group = "io.ekbatan.examples"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public"))
        basePackageName.set("io.ekbatan.core.test.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("com.google.gson.JsonElement")
                    .withBinding("com.example.PostgresJSONGsonBinding")
                    .withIncludeTypes("JSONB"),
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    implementation(project(":ekbatan-core"))

    implementation("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")

    // Add explicit dependency on the JOOQ API
    implementation("org.jooq:jooq-meta")
    implementation("org.jooq:jooq-codegen")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    implementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    implementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    implementation("org.junit.jupiter:junit-jupiter")
    implementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    runtimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    implementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    implementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")

    compileOnly(project(":ekbatan-annotation-processor"))
    annotationProcessor(project(":ekbatan-annotation-processor"))

    implementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    implementation("org.junit.jupiter:junit-jupiter:${project.property("junitJupiterVersion")}")
    runtimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    implementation("io.mockk:mockk:${project.property("mockkVersion")}")
    implementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
