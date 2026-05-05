# JOOQ codegen

Ekbatan uses [`dev.monosoul.jooq-docker`](https://github.com/monosoul/jooq-gradle-plugin) to generate type-safe SQL classes (the `WALLETS` table reference, the `WalletsRecord` type, etc.) from your Flyway migrations. The plugin spins up a throwaway DB container at build time, runs the migrations against it, then introspects the live schema and generates the JOOQ classes against your `build/generated-jooq` directory.

This page is the dialect-by-dialect `build.gradle.kts` reference. For the column types and converters those generated classes use, see [Multi-database](multi-database.md). For when to add a new dialect, see [Adding a new database](multi-database.md#adding-a-new-database).

Each dialect module needs three blocks: the plugin declaration, the container config, and the codegen task. The container config differs per dialect; the rest is largely uniform.

## PostgreSQL

The plugin's default container is Postgres, so no `jooq { withContainer { … } }` block is needed.

```kotlin
plugins {
    id("java")
    id("dev.monosoul.jooq-docker") version "8.0.9"
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("public", "eventlog"))
        basePackageName.set("io.ekbatan.test.<your_module>.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
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
    jooqCodegen("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    // …
}
```

## MariaDB

Explicit `jooq { withContainer { … } }` is required; converter regex tightens to `(?i:JSON)` (not `LONGTEXT`).

```kotlin
jooq {
    withContainer {
        image {
            name = "mariadb:11.8"
            envVars = mapOf(
                "MARIADB_ROOT_PASSWORD" to "root",
                "MARIADB_DATABASE" to "testdb",
            )
        }
        db {
            username = "root"; password = "root"; name = "testdb"; port = 3306
            jdbc { schema = "jdbc:mariadb"; driverClassName = "org.mariadb.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("testdb"))                                  // only generate for tables you'll use
        basePackageName.set("io.ekbatan.test.<your_module>_mariadb.generated.jooq")
        migrationLocations.setFromFilesystem("src/test/resources/db/migration")
        outputDirectory.set(project.layout.buildDirectory.dir("generated-jooq"))
        flywayProperties.put("flyway.placeholderReplacement", "false")
        includeFlywayTable.set(false)
        outputSchemaToDefault.add("testdb")          // generate at root; no `<schema>/` subpackage
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
            )
        }
    }
}

dependencies {
    implementation("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.mariadb.jdbc:mariadb-java-client:${project.property("mariadbJavaClientVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    // …
}
```

## MySQL

Same shape as MariaDB but adds the UUID forced-type entry (`CHAR(36)` → `UUID` via `UuidStringConverter`).

```kotlin
jooq {
    withContainer {
        image {
            name = "mysql:9.4.0"
            envVars = mapOf("MYSQL_ROOT_PASSWORD" to "root", "MYSQL_DATABASE" to "testdb")
        }
        db {
            username = "root"; password = "root"; name = "testdb"; port = 3306
            jdbc { schema = "jdbc:mysql"; driverClassName = "com.mysql.cj.jdbc.Driver" }
        }
    }
}

tasks {
    generateJooqClasses {
        schemas.set(listOf("testdb"))
        // … same Instant + JSON forced types as MariaDB, plus:
        usingJavaConfig {
            database.withForcedTypes(
                // … Instant, JSON entries …
                ForcedType()
                    .withUserType("java.util.UUID")
                    .withConverter("io.ekbatan.core.persistence.jooq.converter.mysql.UuidStringConverter")
                    .withIncludeTypes("CHAR\\(36\\)")
                    .withIncludeExpression(".*\\.id|.*_id"),       // narrow scope: UUID columns only
            )
        }
    }
}

dependencies {
    implementation("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    jooqCodegen("com.mysql:mysql-connector-j:${project.property("mysqlConnectorVersion")}")
    jooqCodegen("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    implementation("org.flywaydb:flyway-mysql:${project.property("flywayVersion")}")
    // …
}
```

## Why these choices

**Why `schemas.set(listOf("testdb"))` excludes `eventlog` for MariaDB/MySQL but PG includes both:** generated classes are only useful where your repository will actually reference them. Modules that manually define `Field<UUID>`/`Field<ObjectNode>` constants for some tables don't need those tables generated — and including them tends to surface dialect-specific JDBC type quirks that aren't worth fighting. Generate only what your repos consume; let manual field definitions handle the rest.

**Why the MySQL UUID forced type uses `withIncludeExpression(".*\\.id|.*_id")`:** unlike Postgres/MariaDB, MySQL has no native UUID type — every UUID column is just `CHAR(36)`. Without an expression filter, the converter would also bind to unrelated `CHAR(36)` columns (handler names, status enums, etc.). Restrict to columns whose name is `id` or ends in `_id`.

**Why the Instant forced type matches `(?i:DATETIME|TIMESTAMP)` on MariaDB but only `TIMESTAMP` on PG:** PG's `TIMESTAMP` maps to `SQLDataType.LOCALDATETIME` directly; MariaDB/MySQL's `DATETIME(6)` reports as `DATETIME` and needs the explicit pattern. The case-insensitive flag handles MariaDB's habit of upper-casing reported type names.

**Why the include patterns use `(?i:...)`:** the JDBC driver's reported type names aren't always consistent in case (`JSON` vs `json`, `DATETIME` vs `datetime`). Case-insensitive regexes save you from chasing those.

## Test container init scripts

For MariaDB/MySQL containers spun up by the codegen plugin or by Testcontainers in tests, init SQL goes in `src/test/resources/<dialect>_init.sql` and is mounted via `withCopyFileToContainer(MountableFile.forClasspathResource("mariadb_init.sql"), "/docker-entrypoint-initdb.d/mariadb_init.sql")`. The container's entrypoint runs every `.sql` in that directory **as root** before the DB becomes ready — the right place for cross-database `GRANT`s. See [Schema vs database](multi-database.md#schema-vs-database) for the full grant idiom.

## See also

- [Multi-database](multi-database.md) — column types, converters, partial indexes, and the rest of the dialect surface
- [Repositories on JOOQ](repositories.md) — what the generated classes feed into
- [Outbox schema](outbox-schema.md) — the canonical migrations these codegen blocks introspect
