# micronaut-wallet-rest-maven-native-pg

A GraalVM native-image variant of [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg). Same domain and same Micronaut wiring — the deltas are all about making the app boot inside the substrate VM, expressed in Maven build-descriptor terms. PG dialect of the Maven Micronaut **native** triple — siblings are [`micronaut-wallet-rest-maven-native-mariadb`](../micronaut-wallet-rest-maven-native-mariadb) and [`micronaut-wallet-rest-maven-native-mysql`](../micronaut-wallet-rest-maven-native-mysql).

## What's different from the JVM sibling

| Concern | JVM sibling | This module |
|---|---|---|
| Dependencies | core + `ekbatan-flyway` + flyway + jdbc | + `io.github.zyraz-io:ekbatan-native` for Jackson3RecordsFeature, HikariCP metadata, jOOQ native support, etc. |
| `Flyway migrator` | `FlywayMigrator.migrate(shardingConfig)` | same call; native runtime gets substrate-VM-aware classpath scanning |
| `native-maven-plugin` build args | n/a | adds `-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,io.example` and `-H:IncludeResources=db/migration/.*\.sql` |
| `micronaut-maven-plugin` | disables JVM AOT executions | leaves the native packaging flow to run Micronaut's native/AOT steps |
| Packaging | `jar` (default) | `${packaging}` — defaults to `jar`; flip to `native-image` for the native build via `-Dpackaging=native-image` |

### Why `FlywayMigrator`, not `Flyway.configure()`

Raw `Flyway.configure().locations("classpath:db/migration").load().migrate()` works on the JVM because the classloader can walk the JAR's classpath: URLs. Inside a GraalVM native image the substrate-VM filesystem can't enumerate `classpath:` directories that way and Flyway aborts with:

```
Unknown prefix for location: classpath:db/migration
```

`FlywayMigrator` (from `ekbatan-flyway`) installs a substrate-VM-aware resource scanner when running native; on the JVM it behaves like normal Flyway configuration. Same code, both binaries.

### Why `-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,io.example`

Ekbatan ships a GraalVM `Feature` called `Jackson3RecordsFeature` that registers reflection metadata for every `record` Jackson 3 needs to deserialize (action params, event payloads). By default it scans only `io.ekbatan`. Without this build arg, native-image fails at runtime with:

```
UnsupportedFeatureError: Record components not available for record class
    io.example.wallet.action.WalletCreateAction$Params
```

The Gradle equivalent in `micronaut-wallet-rest-gradle-native-pg` is `graalvmNative.binaries.all { buildArgs.add("-D…") }`.

### Why `-H:IncludeResources=db/migration/.*\.sql`

native-image's default resource inclusion rules skip arbitrary classpath SQL files. Without this pattern, the migrations don't end up in the image and `FlywayMigrator` finds nothing to apply at startup. The Gradle equivalent is `graalvmNative.binaries.all { resources.includedPatterns.add("db/migration/.*\\.sql") }`.

### Why `combine.children="append"` on the `native-maven-plugin` `<buildArgs>`

The Micronaut parent POM declares `native-maven-plugin` in its `native` profile and adds its own build args there (e.g. `-H:Class=${exec.mainClass}` and reachability-metadata defaults). `combine.children="append"` keeps those and *appends* ours — without it, our config replaces the parent's list and the native image misses critical entries.

## Build & run

GraalVM 25 must be available. The Maven wrapper handles Maven itself, but `native-image` needs a GraalVM JDK on `JAVA_HOME` (or use SDKMAN / asdf to switch).

### JVM mode (same as the JVM sibling)

```bash
docker compose up -d
./mvnw mn:run
./mvnw verify   # JVM-mode integration test
```

### Native binary

```bash
./mvnw package -Dpackaging=native-image
```

This triggers Micronaut's native build pipeline:

1. Compile Java sources.
2. Run the codegen chain (fabric8 docker → flyway-maven → jooq-codegen-maven) to produce JOOQ classes.
3. Run Micronaut's native packaging flow, including its native/AOT steps.
4. Invoke `native-image` with the assembled classpath and our build args.

Output: `target/<artifactId>` — a single-file executable. Run it directly:

```bash
./target/micronaut-wallet-rest-maven-native-pg
```

Native tests (`./mvnw test -Pnative-test` or `nativeTest`) aren't enabled in this example. The JVM-mode test suite covers the same surface; native testing is left as an opt-in for users who specifically need to verify substrate-VM behavior.

## What it shows

Same as the JVM sibling — `Wallet` / `WalletDepositMoneyAction` / `WalletMoneyDepositedEventHandler` / etc. See [`micronaut-wallet-rest-maven-pg/README.md`](../micronaut-wallet-rest-maven-pg/README.md#what-it-shows) for the full table.

## See also

- [`micronaut-wallet-rest-maven-pg`](../micronaut-wallet-rest-maven-pg) — the JVM sibling, same app without the native machinery
- [`micronaut-wallet-rest-maven-native-mariadb`](../micronaut-wallet-rest-maven-native-mariadb) / [`micronaut-wallet-rest-maven-native-mysql`](../micronaut-wallet-rest-maven-native-mysql) — the MariaDB / MySQL siblings in the native triple
- [`micronaut-wallet-rest-gradle-native-pg`](../micronaut-wallet-rest-gradle-native-pg) — the Gradle Micronaut native counterpart
- [`spring-boot-wallet-rest-gradle-native-pg`](../spring-boot-wallet-rest-gradle-native-pg) — Spring Boot Gradle native counterpart, same substrate-VM concerns
- [Ekbatan docs › Runtime › GraalVM native-image](../../docs/runtime/native-image.md) — the framework's native-image story end-to-end
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md)
- [Ekbatan docs › Getting started with Maven](../../docs/maven/getting-started.md)
