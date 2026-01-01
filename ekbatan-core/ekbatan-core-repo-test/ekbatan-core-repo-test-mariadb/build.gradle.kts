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

jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars =
                mapOf(
                    "MARIADB_ROOT_PASSWORD" to "root",
                    "MARIADB_DATABASE" to "ekdb",
                )
        }
        db {
            username = "root"
            password = "root"
            name = "ekdb"
            port = 3306
            jdbc {
                schema = "jdbc:mariadb"
                driverClassName = "org.mariadb.jdbc.Driver"
            }
        }
    }
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
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    implementation(project(":ekbatan-core"))

    implementation("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")

    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")

    // Add explicit dependency on the JOOQ API
    implementation("org.jooq:jooq-meta")
    implementation("org.jooq:jooq-codegen")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")

    testImplementation("org.testcontainers:testcontainers-junit-jupiter:${project.property("testcontainersVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

    testImplementation(project(":ekbatan-core:ekbatan-core-repo-test"))
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-mariadb:${project.property("testcontainersVersion")}")

    testCompileOnly(project(":ekbatan-annotation-processor"))
    testAnnotationProcessor(project(":ekbatan-annotation-processor"))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
