# spring-boot-wallet-rest-gradle-native-pg

The GraalVM native-image variant of [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg). The domain code is identical — only the build configuration and one small AOT guard differ. Start with the JVM sibling if you're new to Ekbatan; come back here once you want a native binary.

## What it shows

| Surface | Class |
|---|---|
| `Model` (event-emitting) | `Wallet` |
| `Entity` (no events) | `Notification` |
| `Action` | `WalletCreateAction`, `WalletDepositMoneyAction`, `WalletCloseAction`, `CreateNotificationAction` |
| `EventHandler` | `WalletMoneyDepositedEventHandler` (listen-to-yourself; invokes `CreateNotificationAction`) |
| `Repository` | `WalletRepository`, `NotificationRepository` |
| REST | `WalletController` |
| Flyway migration | `FlywayConfiguration` — uses `FlywayHelper` (native-aware) and an AOT-time guard |
| Integration test (JVM) | `./gradlew test` |
| Integration test (native) | `./gradlew nativeTest` |

## What this project adds on top of the JVM sibling

- **`org.graalvm.buildtools.native` Gradle plugin** — provides `nativeCompile` / `nativeRun` / `nativeTest`. The Spring Boot plugin auto-applies its AOT integration once this is on the classpath.
- **`io.github.zyraz-io:ekbatan-native` dependency** — ships GraalVM Features (Jackson 3 record reflection, jOOQ array-type fix, Flyway resource provider, etc.) that auto-apply when native-image runs.
- **`FlywayHelper.migrate(...)`** instead of `Flyway.configure(...)` — inside a native image, raw Flyway can't walk classpath migrations through the substrate-VM filesystem; `FlywayHelper` installs a resource provider that can.
- **AOT-time guard** on the Flyway bean — Spring AOT runs `Application.main(...)` at build time to snapshot the bean factory, and we don't want the factory method to open a JDBC connection then.
- **`graalvmNative { ... }` block** — requires a Java 25 toolchain that can run `native-image` (auto-detected from SDKMAN/asdf/system installs via Gradle's `javaToolchains`), bundles `db/migration/*.sql` into the image, enables the published reachability-metadata repository, and tells `Jackson3RecordsFeature` to scan `io.example` (in addition to its default `io.ekbatan` scan root) so record components for our `Action.Params` records are reflectively registered.

## Run locally (JVM mode)

Same as the sibling — Docker only:

```bash
./gradlew bootRun
./gradlew test
```

## Run as a native image

Install a GraalVM 25 toolchain — SDKMAN works:

```bash
sdk install java 25.0.3-graal
```

You usually don't need to set `JAVA_HOME` — Gradle's toolchain auto-detection picks installed native-image-capable JDKs up. If you have several Java 25 installs and Gradle picks the wrong one, set `JAVA_HOME` to the GraalVM install before running the native task. Then:

```bash
./gradlew nativeCompile             # produces build/native/nativeCompile/spring-boot-wallet-rest-gradle-native-pg (~120 MB, ~2 minutes)
./gradlew nativeRun                 # builds + runs the native binary
./gradlew nativeTest                # compiles + runs the test suite as a native image (~2 minutes)
```

The native binary brings up the same Postgres container declared in `compose.yaml` on startup via Spring Boot's docker-compose integration. `nativeTest` uses Testcontainers in the test binary just like the JVM test path does.

## See also

- [`spring-boot-wallet-rest-gradle-pg`](../spring-boot-wallet-rest-gradle-pg) — the JVM-only baseline.
- [Ekbatan docs › GraalVM native-image](../../docs/runtime/native-image.md) — framework-level notes on Jackson 3 record reflection, jOOQ array-type fix, Flyway classpath scanning, scan-package overrides, and other native-specific concerns.
- [Ekbatan docs › Wiring with Spring Boot](../../docs/wiring/spring.md) — the framework's Spring Boot integration guide (applies to both this project and the JVM sibling).
