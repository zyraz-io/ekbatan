# quarkus-wallet-rest-gradle-native-mariadb

The GraalVM native-image variant of [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb). The domain
code is identical — only the build configuration and a small set of native-specific extras
differ. Start with the JVM sibling if you're new to the framework; come back here once you
want a native binary.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` (listen-to-yourself; invokes `CreateNotificationAction`) |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST (JAX-RS) | `WalletResource` |
| Flyway migration (CDI) | `FlywayConfiguration` |
| Integration test (JVM) | `WalletResourceIntegrationTest` — `./gradlew test` |
| Integration test (jar / native) | `WalletResourceNativeIT` — `./gradlew quarkusIntTest` |

## What this project adds on top of the JVM sibling

- **`io.github.zyraz-io:ekbatan-native` dependency** — ships GraalVM Features (Jackson 3 record
  reflection, jOOQ array-type fix, Flyway resource provider for substrate-VM classpaths, etc.)
  that auto-apply when native-image runs.
- **`src/integrationTest/java` source set with `WalletResourceNativeIT`** — `@QuarkusIntegrationTest`
  drives the packaged binary out-of-process via REST-assured (the native binary runs in its own
  process, so `@Inject` can't bridge the test JVM to the app).
- **`build.gradle.kts` excludes `*IT.class` from the regular `test` task** so JUnit Platform's
  default discovery doesn't pick the native ITs into JVM-mode runs.
- **`sourceSets.integrationTest` wires the `test` source set output onto its classpath** so
  `MariaDBTestResource` (in `src/test/java`) is reachable from the IT.

That's it — no extra GraalVM toolchain pinning needed (Quarkus's Gradle plugin manages that
internally), no AOT-time guard on the Flyway bean (Quarkus's build-time AOT doesn't invoke
producer methods the way Spring AOT does).

## Run locally (JVM mode)

Docker only:

```bash
./gradlew quarkusDev           # dev mode with live reload
./gradlew test                 # JVM @QuarkusTest
./gradlew quarkusIntTest       # @QuarkusIntegrationTest against the packaged jar
```

## Build / test a native image

Install a GraalVM 25 toolchain. Quarkus's Gradle plugin doesn't (yet) use Gradle's toolchain
auto-detection for native — it looks for `native-image` via `GRAALVM_HOME` / `JAVA_HOME` /
`PATH`. SDKMAN works:

```bash
sdk install java 25.0.3-graal
GRAALVM_HOME=$HOME/.sdkman/candidates/java/25.0.3-graal ./gradlew testNative
```

The native binary itself **builds successfully** (~2 minutes; ~120 MB executable produced at
`build/<artifact>-runner`). You can run it directly to serve the wallet REST API natively.

### Known native-runtime caveat (`testNative`)

`testNative` currently boots the native binary and the binary fails at startup — Flyway's
ServiceLoader-based plugin discovery doesn't survive native-image without additional
reachability metadata. Pulling in `quarkus-flyway` to get that metadata works around the
Flyway issue but trips a separate HikariCP-7-on-native issue (`AtomicReference<Credentials>`
snapshot, the same NPE the framework's own
`ekbatan-integration-tests/di/quarkus/src/main/resources/application.properties` documents).
Both are framework-side gaps — neither has a clean fix in user code today. The parent
project's own Quarkus native test uses Postgres and works; the MariaDB path here is new
territory and needs framework-level reachability metadata + a Hikari class-init carve-out to
be added.

Workable today: `quarkusIntTest` (JVM mode against the packaged jar) covers the same
assertions and passes.

## See also

- [`quarkus-wallet-rest-gradle-mariadb`](../quarkus-wallet-rest-gradle-mariadb) — the JVM-only baseline. Start there.
- [`spring-boot-wallet-rest-gradle-native-pg`](../spring-boot-wallet-rest-gradle-native-pg) — the equivalent native
  packaging for the Spring Boot variant.
- [Ekbatan docs › GraalVM native-image](../../docs/runtime/native-image.md) — framework-level
  notes on Jackson 3 records, jOOQ, Flyway, etc.
- [Ekbatan docs › Wiring with Quarkus](../../docs/wiring/quarkus.md) — the framework's Quarkus
  integration guide.
