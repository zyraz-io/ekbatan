import org.jooq.meta.jaxb.ForcedType

plugins {
    id("java")
    id("com.revolut.jooq-docker") version "0.3.12"
}

group = "io.ekbatan.examples"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

tasks {
    generateJooqClasses {
        schemas = arrayOf("public")
        basePackageName = "io.ekbatan.examples.generated.jooq"
        inputDirectory.setFrom(project.files("src/main/resources/db/migration"))
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties = mapOf("flyway.placeholderReplacement" to "false")
        excludeFlywayTable = true
        customizeGenerator {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("com.google.gson.JsonElement")
                    .withBinding("com.example.PostgresJSONGsonBinding")
                    .withIncludeTypes("JSONB"),
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.repository.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    implementation(project(":ekbatan-core"))

    implementation("org.jooq:jooq:3.20.8")
    implementation("org.jooq:jooq-codegen")

    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.testcontainers:testcontainers:2.0.1")
    testImplementation("org.testcontainers:postgresql:1.21.3")

    // JOOQ JDBC driver
    jdbc("org.postgresql:postgresql:42.7.3")

    // Add explicit dependency on the JOOQ API
    implementation("org.jooq:jooq-meta")
    implementation("org.jooq:jooq-codegen")

    // Flyway for database migrations
    implementation("org.flywaydb:flyway-core:11.16.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.16.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

jooq {
    image {
        repository = "postgres"
        tag = "15-alpine"
        envVars =
            mapOf(
                "POSTGRES_DB" to "testdb",
                "POSTGRES_USER" to "test",
                "POSTGRES_PASSWORD" to "test",
            )
    }

    db {
        username = "test"
        password = "test"
        name = "testdb"
    }

    jdbc {
        schema = "jdbc:postgresql"
        driverClassName = "org.postgresql.Driver"
        jooqMetaName = "org.jooq.meta.postgres.PostgresDatabase"
    }
}
