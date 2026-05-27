import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("io.quarkus") version "3.34.6"
    id("dev.monosoul.jooq-docker") version "8.0.9"
    id("com.diffplug.spotless") version "8.1.0"
}

group = "io.example"
version = "0.0.3-SNAPSHOT"

// jOOQ codegen ships 3.20.x; pin runtime jOOQ to match.
extra["jooq.version"] = "3.20.10"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

val ekbatanVersion: String by project
val quarkusVersion = "3.34.6"
val postgresqlVersion = "42.7.10"
val flywayVersion = "12.0.0"

// The dev.monosoul.jooq-docker plugin defaults to a Postgres container, so no explicit
// `jooq { withContainer { … } }` block is needed for this project. The plugin spins up the
// throwaway container, runs Flyway migrations against it, then introspects the live schema
// and writes JOOQ classes into build/generated-jooq/.

dependencies {
    // ── Quarkus BOM ─────────────────────────────────────────────────────────
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    // ── Ekbatan ─────────────────────────────────────────────────────────────
    implementation("io.github.zyraz-io:ekbatan-quarkus:$ekbatanVersion")
    compileOnly("io.github.zyraz-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.zyraz-io:ekbatan-annotation-processor:$ekbatanVersion")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.20.0")

    // ── Quarkus REST ────────────────────────────────────────────────────────
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ── PostgreSQL ──────────────────────────────────────────────────────────
    // Ekbatan's ConnectionProvider runs Hikari itself — we don't use Quarkus's data source —
    // but the driver still needs to be on the classpath for Hikari's Class.forName(...).
    implementation("org.postgresql:postgresql:$postgresqlVersion")
    jooqCodegen("org.postgresql:postgresql:$postgresqlVersion")

    // ── Flyway ──────────────────────────────────────────────────────────────
    // Run programmatically via FlywayConfiguration so the same `ekbatan.sharding.*` block
    // drives both migrations and Ekbatan's runtime pools.
    // Quarkus's official Flyway extension — runs migrations at app startup via
    // `quarkus.flyway.migrate-at-start=true` against the datasource overridden by
    // `EkbatanShardFlywayCustomizer` to point at the default shard's primaryConfig. The
    // extension transitively brings flyway-core at Quarkus's BOM-pinned version (12.0.0).
    implementation("io.quarkus:quarkus-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // JDBC driver: quarkus-jdbc-postgresql pulls the driver JAR + registers the class
    // reflectively for native (no-op in JVM but harmless).
    implementation("io.quarkus:quarkus-jdbc-postgresql")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("io.quarkus:quarkus-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.81.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json", "*.properties")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.example.wallet.generated.jooq")
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

    withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
    }

    withType<Test> {
        useJUnitPlatform()
        // Quarkus uses jboss-logmanager — telling JUL to delegate to it keeps test logs sane.
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    }
}

sourceSets {
    main {
        java {
            srcDir(tasks.generateJooqClasses.flatMap { it.outputDirectory })
        }
    }
}

// Force Flyway to the requested version even when the framework's BOM (Quarkus's
// quarkus-bom, Spring Boot's spring-boot-dependencies, Micronaut's micronaut-bom) pins
// an older one — they all do, to varying degrees:
//   - Quarkus 3.34.6 pins flyway-core to 12.0.0
//   - Spring Boot 4.x      pins flyway-core to 11.14.1
// Without this force, `org.flywaydb:flyway-core` gets resolved DOWN to the BOM's choice
// and the runtime + jOOQ-codegen versions drift apart. Read from gradle.properties
// directly so Spring Boot projects (which don't declare a local `flywayVersion` val,
// since they let the BOM manage the version) work too.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.flywaydb") {
            useVersion(flywayVersion)
            because("ekbatan tracks Flyway $flywayVersion across all examples")
        }
    }
}
