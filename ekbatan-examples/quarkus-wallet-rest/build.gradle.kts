import org.jooq.meta.jaxb.ForcedType

plugins {
    java
    id("io.quarkus") version "3.34.6"
    id("dev.monosoul.jooq-docker") version "8.0.9"
    id("com.diffplug.spotless") version "8.1.0"
}

group = "io.example"
version = "0.0.2-SNAPSHOT"

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
val mariadbDriverVersion = "3.5.7"
val flywayVersion = "11.20.0"

// ── jOOQ codegen container ─────────────────────────────────────────────────────────
// The plugin spins up a throwaway MariaDB container at build time, runs the Flyway migrations
// against it, then introspects the live schema and generates the JOOQ classes. The runtime
// app classpath uses the same MariaDB JDBC driver below.
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars =
                mapOf(
                    "MARIADB_ROOT_PASSWORD" to "root",
                    "MARIADB_DATABASE" to "wallet",
                )
        }
        db {
            username = "root"
            password = "root"
            name = "wallet"
            port = 3306
            jdbc {
                schema = "jdbc:mariadb"
                driverClassName = "org.mariadb.jdbc.Driver"
            }
        }
    }
}

dependencies {
    // ── Quarkus BOM ─────────────────────────────────────────────────────────
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:$quarkusVersion"))

    // ── Ekbatan ─────────────────────────────────────────────────────────────
    // The Quarkus extension pulls ekbatan-core, the annotation processor, the
    // local-event-handler, and distributed-jobs transitively.
    implementation("io.github.zyraz-io:ekbatan-quarkus:$ekbatanVersion")
    // The annotation processor jar needs to be on BOTH paths: `annotationProcessor` so javac
    // runs the @AutoBuilder processor, and `implementation` so the @AutoBuilder annotation
    // itself is visible to the compiler when the source code is parsed.
    implementation("io.github.zyraz-io:ekbatan-annotation-processor:$ekbatanVersion")
    annotationProcessor("io.github.zyraz-io:ekbatan-annotation-processor:$ekbatanVersion")

    // Used by the domain classes for Validate.notNull / Validate.isTrue style guards.
    implementation("org.apache.commons:commons-lang3:3.20.0")

    // ── Quarkus REST ────────────────────────────────────────────────────────
    // quarkus-rest is the modern (formerly RESTEasy-Reactive) JAX-RS impl;
    // quarkus-rest-jackson plugs Jackson into the request/response (de)serializers.
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ── MariaDB ─────────────────────────────────────────────────────────────
    // Ekbatan's ConnectionProvider runs Hikari itself — we don't use Quarkus's data source —
    // but the driver still needs to be on the classpath for Hikari's Class.forName(...).
    implementation("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:$mariadbDriverVersion")

    // ── Flyway ──────────────────────────────────────────────────────────────
    // Flyway needs flyway-mysql to recognize the MariaDB JDBC URL. Run programmatically (see
    // FlywayConfiguration) rather than via quarkus-flyway — that one binds to
    // quarkus.datasource.* / quarkus.flyway.* and would duplicate the connection config Ekbatan
    // already reads from ekbatan.sharding.*
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-mysql:$flywayVersion")
    jooqCodegen("org.flywaydb:flyway-mysql:$flywayVersion")

    // ── Tests ───────────────────────────────────────────────────────────────
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testImplementation("org.awaitility:awaitility:4.3.0")
    // Note: in testcontainers 2.x the modules were renamed:
    //   org.testcontainers:mariadb          → org.testcontainers:testcontainers-mariadb
    //   org.testcontainers:junit-jupiter    → org.testcontainers:testcontainers-junit-jupiter
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat("2.81.0")
    }
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

tasks {
    generateJooqClasses {
        // Only generate for the main 'wallet' database — the eventlog tables are accessed
        // through the framework's own field constants, no JOOQ codegen for them needed.
        schemas.set(listOf("wallet"))
        basePackageName.set("io.example.wallet.generated.jooq")
        migrationLocations.setFromFilesystem("src/main/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        // No subpackage for the default schema on MariaDB/MySQL — table classes land at
        // io.example.wallet.generated.jooq.tables.Wallets etc.
        outputSchemaToDefault.add("wallet")
        usingJavaConfig {
            database.withForcedTypes(
                ForcedType()
                    .withUserType("java.time.Instant")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.InstantConverter")
                    .withIncludeTypes("(?i:DATETIME|TIMESTAMP)")
                    .withIncludeExpression(".*"),
                ForcedType()
                    .withUserType("tools.jackson.databind.node.ObjectNode")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.JSONObjectNodeConverter")
                    .withIncludeTypes("(?i:JSON)")
                    .withIncludeExpression(".*"),
                // No UUID forced type needed — MariaDB has a native UUID type (10.7+) and
                // jOOQ maps it to java.util.UUID directly. Contrast with the MySQL example,
                // which has to convert CHAR(36) → UUID via UuidStringConverter.
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
