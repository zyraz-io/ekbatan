import dev.monosoul.jooq.RecommendedVersions
import org.jooq.meta.jaxb.ForcedType


plugins {
    id("java")
    id("dev.monosoul.jooq-docker") version "8.0.3"
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
    jooqCodegen("org.postgresql:postgresql:42.7.7")

    // Add explicit dependency on the JOOQ API
    implementation("org.jooq:jooq-meta")
    implementation("org.jooq:jooq-codegen")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:11.16.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.16.0")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.testcontainers:testcontainers:2.0.1")
    testImplementation("org.testcontainers:postgresql:1.21.3")

    testCompileOnly(project(":ekbatan-annotation-processor"))
    testAnnotationProcessor(project(":ekbatan-annotation-processor"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
