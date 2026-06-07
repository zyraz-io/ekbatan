# GraalVM native-image

Ekbatan native-image support is split across your build tool, your framework integration, and the `ekbatan-native` module. Native-image support is not a different execution model for Ekbatan: actions, repositories, optimistic locking, sharding, and outbox writes behave the same as on the JVM. The native-specific work is making GraalVM aware of reflection targets, SQL resources, native-friendly Flyway behavior, and the libraries used by the selected stack.

## The moving parts

| Part | Responsibility |
|---|---|
| GraalVM JDK 25 | Provides the `native-image` compiler. |
| Stack native plugin | Spring Boot, Quarkus, Micronaut, or GraalVM Build Tools decide how the application binary/test binary is built. |
| `ekbatan-native` | Registers Ekbatan/Jackson/jOOQ/Kafka/Avro/Testcontainers metadata and provides `FlywayHelper` for raw Flyway. |
| Scan package build arg | Tells Ekbatan's native features where your application records, builders, events, and generated jOOQ classes live. |
| Resource inclusion | Ensures `db/migration/*.sql` and init scripts are bundled into the native image. |

## Required app configuration

### Add `ekbatan-native`

Add the module to applications that build native binaries:

```kotlin
implementation("io.github.zyraz-io:ekbatan-native:0.1.2")
```

or Maven:

```xml
<dependency>
  <groupId>io.github.zyraz-io</groupId>
  <artifactId>ekbatan-native</artifactId>
  <version>0.1.2</version>
</dependency>
```

### Set scan packages

The default scan root is `io.ekbatan`. Add your application root package at native-image build time:

| Consumer | Setting |
|---|---|
| Spring Boot / Micronaut / plain Gradle | `graalvmNative.binaries.all { buildArgs.add("-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,com.your.package") }` |
| Quarkus | `quarkus.native.additional-build-args=-Dio.ekbatan.graalvm.scan.packages=io.ekbatan\,com.your.package` |
| Maven native-maven-plugin | `<buildArg>-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,com.your.package</buildArg>` |

If this is missing, native runtime failures usually mention record components, action params records, event payload records, builder methods, or generated jOOQ classes.

### Include SQL resources

If Flyway runs from classpath migrations, include them in the image:

```kotlin
graalvmNative {
    binaries.all {
        resources.includedPatterns.add("db/migration/.*\\.sql")
        resources.includedPatterns.add(".*_init\\.sql")
    }
}
```

Maven equivalent:

```xml
<buildArg>-H:IncludeResources=db/migration/.*\.sql</buildArg>
```

Use a different pattern if your app stores migrations somewhere else.

### Use a native-image-capable toolchain

Gradle native examples require Java 25 and `nativeImageCapable.set(true)`, not a hard-coded GraalVM vendor. That works locally with SDKMAN/asdf/system GraalVM installs and also works in CI with `actions/setup-java` using `distribution: graalvm`.

If Gradle chooses the wrong Java 25 installation, set `JAVA_HOME` to the GraalVM JDK or pin the build with:

```bash
./gradlew -Dorg.gradle.java.installations.paths="$JAVA_HOME" \
  -Dorg.gradle.java.installations.auto-detect=false \
  nativeTest
```

## What `ekbatan-native` auto-loads

Each feature is registered through `META-INF/native-image/.../native-image.properties`. Features detect their target libraries and no-op when the library is absent.

| Feature | Triggers when classpath contains | Registers |
|---|---|---|
| `Jackson3RecordsFeature` | Always | Java records, `@AutoBuilder` builder classes, classes with `@JsonCreator`, and classes in `.generated.jooq.` packages. |
| `KafkaClientsFeature` | `org.apache.kafka.clients.consumer.KafkaConsumer` | Kafka security and serialization packages plus default partitioners/assignors referenced by Kafka config strings. |
| `AvroSpecificRecordFeature` | `org.apache.avro.specific.SpecificRecord` | Avro `SpecificRecord` implementations under the configured Avro scan packages. |
| `TestcontainersDockerJavaFeature` | `org.testcontainers.DockerClientFactory` | docker-java API/model/command packages, shaded and unshaded. |

`ekbatan-native` also bundles HikariCP reachability metadata. The jOOQ native substitution for `Internal.arrayType(...)` lives in `ekbatan-core` and is applied automatically.

## Jackson 3 records and builders

Ekbatan serializes events with Jackson 3 (`tools.jackson.databind.*`). Jackson needs reflection metadata for records, action params, event payloads, generated builders, and some value factories. `Jackson3RecordsFeature` scans the configured packages and registers both:

- bulk query metadata, so reflection queries such as `getDeclaredMethods()` work; and
- per-member invocation metadata, so Jackson can actually invoke constructors, methods, and record accessors at runtime.

Both are needed on GraalVM 25.

## Flyway on native

Flyway's normal classpath scanner does not always work inside a native image because classpath resources are not exposed as normal `file:` or `jar:` directories. Ekbatan supports two patterns, and the examples use them differently by stack.

| Stack | Recommended pattern |
|---|---|
| Spring Boot | Use `spring-boot-starter-flyway` and provide a `@FlywayDataSource` bean built from `ekbatan.sharding.*`. Do not run Flyway manually from `@PostConstruct`. |
| Quarkus | Use `quarkus-flyway` and an `EkbatanShardFlywayCustomizer` that points Flyway at the Ekbatan shard config. Do not run raw Flyway from startup observers. |
| Micronaut | The native examples use a small startup migrator that calls `FlywayHelper.migrate(...)`. They keep `micronaut-flyway` on the classpath for Flyway/native dependencies and hints, but do not use a `flyway:` auto-config block. |
| Plain Java / raw tests | Use `FlywayHelper.migrate(...)` directly. |

`FlywayHelper` is a wrapper around normal Flyway configuration. On the JVM it behaves like inline `Flyway.configure().dataSource(...).locations(...).load().migrate()`. In a native image it installs `NativeImageFlywayResourceProvider`, which can walk bundled `classpath:` migrations.

```java
import io.ekbatan.graalvm.flyway.FlywayHelper;

FlywayHelper.migrate(jdbcUrl, username, password);
FlywayHelper.migrate(jdbcUrl, username, password, "classpath:db/migration", "classpath:db/seed");
```

## Build and test commands

The exact command depends on the stack:

| Stack/build | Build native app | Native verification |
|---|---|---|
| Spring Boot Gradle | `./gradlew nativeCompile` | `./gradlew nativeTest` |
| Spring Boot Maven | `./mvnw -Pnative native:compile` | `./mvnw -PnativeTest test` |
| Quarkus Gradle | `./gradlew build -Dquarkus.native.enabled=true` | `./gradlew testNative` |
| Quarkus Maven | `./mvnw -Dnative package` | `./mvnw -Dnative verify` |
| Micronaut Gradle | `./gradlew nativeCompile` | `./gradlew nativeTest` |
| Micronaut Maven | `./mvnw -Dpackaging=native-image -DskipTests package` | Native tests are not enabled in the Maven Micronaut examples. |

Ekbatan's Heavy Verification workflow runs JVM tests plus native builds/tests for the examples. The root Gradle native sweep uses:

```bash
./gradlew nativeTest --parallel --max-workers=4 --continue --stacktrace
```

Use that as heavy verification, not as the normal edit-compile-test loop.

## Troubleshooting

- **`native-image` is missing or Gradle selects Temurin/OpenJDK.** Use a Java 25 GraalVM JDK and make sure Gradle sees the `native-image` capable installation.
- **Jackson cannot deserialize action params or events.** Add your application package to `io.ekbatan.graalvm.scan.packages`.
- **Flyway sees no migrations.** Include `db/migration/*.sql` as native resources and use the Flyway pattern for your stack.
- **Quarkus and HikariCP.** Quarkus prefers Agroal and does not consume all generic RMR metadata automatically. Depending on `ekbatan-native` is the simplest way to bring Ekbatan's HikariCP metadata into the native classpath.
- **Micronaut native tests and Hikari DEBUG logging.** The Micronaut native examples ship a minimal `logback.xml` that keeps Hikari below DEBUG, avoiding reflection over every JavaBean property during startup.

## See also

- [Wiring with Spring Boot](../wiring/spring.md) - Spring AOT and Flyway details
- [Wiring with Quarkus](../wiring/quarkus.md) - Quarkus native and Flyway details
- [Wiring with Micronaut](../wiring/micronaut.md) - Micronaut native and serde details
- [`ekbatan-examples/*-native-*`](../../ekbatan-examples) - runnable native wallet examples
