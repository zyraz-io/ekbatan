import dev.monosoul.jooq.RecommendedVersions
import org.jooq.meta.jaxb.ForcedType

/**
 * Shared widget domain (action, repository, handler, models, events) plus the jOOQ-generated
 * Postgres tables and the Flyway migration scripts that build the `widgets` / `eventlog.events` /
 * `eventlog.event_notifications` / `scheduled_tasks` tables.
 *
 * <p>Each DI-flavor integration test (`spring-boot-starter`, `quarkus`, `micronaut`) used to ship
 * its own copy of these files under a per-flavor package — three nearly-identical `WidgetCreate
 * Action` / `WidgetRepository` / `WidgetCreatedCounterHandler` trees + three sets of jOOQ codegen
 * + three copies of the same SQL. This module is the single source of truth.
 *
 * <p>The flavor modules pull this in via plain `implementation(project(":ekbatan-integration-tests:
 * di:shared"))` and only contribute their framework-specific test wiring (Spring's
 * `@DynamicPropertySource`, Quarkus's `QuarkusTestResourceLifecycleManager`, Micronaut's
 * `TestPropertyProvider`) plus the `@*Test` integration test class.
 */
plugins {
    `java-library`
    id("dev.monosoul.jooq-docker") version "8.0.9"
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

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.ekbatan.test.di.shared.generated.jooq")
        // Migrations live next to the runtime classpath so Flyway in each flavor's test can also
        // load them via `classpath:db/migration` — same physical files, two consumers.
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("public")
        schemaToPackageMapping.put("public", "public_schema")
        schemaToPackageMapping.put("eventlog", "eventlog_schema")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("TIMESTAMP")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONBObjectNodeConverter")
                    .withIncludeTypes("JSONB")
                    .withIncludeExpression(".*"),
            )
        }
    }
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di:annotations"))
    api(project(":ekbatan-events:local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    // Micronaut compile-time bean processing — runs the EkbatanStereotypeVisitor at *this*
    // module's compile time so the user-side @EkbatanAction / @EkbatanRepository /
    // @EkbatanEventHandler classes get a $Definition class generated and shipped in the shared
    // jar. Without this, the downstream :di:micronaut test would see no Micronaut beans for the
    // shared widget classes (Micronaut's Java AP can only process the module being compiled,
    // not classpath dependencies). Inert for Spring and Quarkus consumers — they don't read
    // Micronaut's BeanDefinitionReference files.
    annotationProcessor("io.micronaut:micronaut-inject-java:${project.property("micronautVersion")}")
    annotationProcessor(project(":ekbatan-di:micronaut"))
    compileOnly("io.micronaut:micronaut-inject:${project.property("micronautVersion")}")

    api("org.jooq:jooq:${RecommendedVersions.JOOQ_VERSION}")
    api("org.jooq:jooq-meta")
    api("org.jooq:jooq-codegen")

    api("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")

    api("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
    api("org.flywaydb:flyway-database-postgresql:${project.property("flywayVersion")}")

    api("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")

    // The widget action's Action subclass + AutoBuilder generation needs the Ekbatan AP.
    annotationProcessor(project(":ekbatan-annotation-processor"))
}
