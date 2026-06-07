# micronaut-wallet-rest-gradle-native-pg

A GraalVM native-image variant of [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg). Same domain, same wiring — the deltas are all about making the app boot inside the substrate VM.

## What's different from the JVM sibling

| Concern | JVM sibling | This module |
|---|---|---|
| Build plugins | `io.micronaut.application` | same — the plugin auto-applies `org.graalvm.buildtools.native` |
| Dependencies | core + `ekbatan-flyway` + flyway + jdbc | + `io.github.zyraz-io:ekbatan-native` for Jackson3RecordsFeature, HikariCP metadata, jOOQ native support, etc. |
| `Flyway migrator` | `FlywayMigrator.migrate(shardingConfig)` | same call; native runtime gets substrate-VM-aware classpath scanning |
| `graalvmNative {}` block | n/a | requires a Java 25 `native-image`-capable launcher, bundles `db/migration/*.sql` into the image, points Jackson3RecordsFeature at `io.example.*` |

### Why `FlywayMigrator`, not `Flyway.configure()`

Raw `Flyway.configure().locations("classpath:db/migration").load().migrate()` works on the JVM because the classloader can walk the JAR's classpath: URLs. Inside a GraalVM native image the substrate-VM filesystem can't enumerate `classpath:` directories that way and Flyway aborts with:

```
Unknown prefix for location: classpath:db/migration
```

`FlywayMigrator` (from `ekbatan-flyway`) installs a substrate-VM-aware resource scanner when running native; on the JVM it behaves like normal Flyway configuration. Same code, both binaries.

### Why `buildArgs.add("-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,io.example")`

Ekbatan ships a GraalVM `Feature` called `Jackson3RecordsFeature` that registers reflection metadata for every `record` Jackson 3 needs to deserialize (action params, event payloads). By default it scans only `io.ekbatan`. Without this build arg, native-image fails at runtime with:

```
UnsupportedFeatureError: Record components not available for record class
    io.example.wallet.action.WalletCreateAction$Params
```

The same trick is documented in [`spring-boot-wallet-rest-gradle-native-pg`](../spring-boot-wallet-rest-gradle-native-pg).

### Why `resources.includedPatterns.add("db/migration/.*\\.sql")`

native-image's default resource inclusion rules skip arbitrary classpath SQL files. Without this pattern, the migrations don't end up in the image and `FlywayMigrator` finds nothing to apply at startup.

## Build & run

### JVM mode (same as the JVM sibling)

```bash
docker compose -f compose.yaml up -d
./gradlew run
./gradlew test
```

### Native binary

GraalVM 25 must be available — Gradle's toolchain auto-detection picks up SDKMAN / asdf / system installs, so `JAVA_HOME` juggling usually isn't required. If you have several Java 25 installs and Gradle picks the wrong one, set `JAVA_HOME` to the GraalVM install before running the native task.

```bash
./gradlew nativeCompile          # produces build/native/nativeCompile/<app>
./gradlew nativeRun              # builds (if needed) + runs the binary
```

For the native test image:

```bash
./gradlew nativeTest             # rebuilds and runs the test suite as a native image
```

Note that `nativeTest` is significantly slower than `test` (the test image is rebuilt from scratch) and has a higher chance of surfacing native-image-only quirks. Treat `./gradlew test` (JVM tests) as the fast feedback loop; reserve `nativeTest` for pre-release verification.

## See also

- [`micronaut-wallet-rest-gradle-pg`](../micronaut-wallet-rest-gradle-pg) — the JVM sibling, same app without the native machinery
- [`spring-boot-wallet-rest-gradle-native-pg`](../spring-boot-wallet-rest-gradle-native-pg) — Spring counterpart with the same native concerns
- [`quarkus-wallet-rest-gradle-native-mariadb`](../quarkus-wallet-rest-gradle-native-mariadb) — Quarkus counterpart (and the only example currently blocked on framework-side native gaps)
- [Ekbatan docs › Runtime › GraalVM native-image](../../docs/runtime/native-image.md) — the framework's native-image story end-to-end
- [Ekbatan docs › Wiring with Micronaut](../../docs/wiring/micronaut.md) — the framework's Micronaut integration guide
