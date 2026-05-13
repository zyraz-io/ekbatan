# Wiring with Micronaut

What you write in a Micronaut app to use Ekbatan: the integration jar plus the framework's compile-time annotation processor, four `@Ekbatan*` annotations on your own classes, and an `application.yml` tree under `ekbatan.*`. The compile-time `EkbatanStereotypeVisitor` lifts your annotated classes to `@Singleton` so Micronaut generates `BeanDefinition`s for them, and `ActionExecutor` is injectable anywhere.

For the equivalent in plain Java with no DI container, see [wiring/without-di.md](without-di.md). For Spring Boot and Quarkus, see [spring.md](spring.md) and [quarkus.md](quarkus.md).

## What you write

### 1. The integration jar + annotation processor

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zyraz-io:ekbatan-micronaut:<version>")
    annotationProcessor("io.github.zyraz-io:ekbatan-micronaut:<version>")
    annotationProcessor("io.micronaut:micronaut-inject-java")
}
```

(Published on Maven Central under groupId `io.github.zyraz-io`. Java packages stay `io.ekbatan.*` — they don't need to match the Maven groupId.)

The `annotationProcessor` line is **required** — without it, the `EkbatanStereotypeVisitor` doesn't run during your compile, so your `@Ekbatan*` classes never get lifted to `@Singleton` and Micronaut produces no `BeanDefinition`s for them.

The `implementation` jar transitively pulls in `ekbatan-core`, `ekbatan-events:local-event-handler`, `ekbatan-distributed-jobs`, and the `@Ekbatan*` annotation jar. Add `ekbatan-keyed-lock-redis` separately if you want the Redis-backed lock provider.

### 2. Your domain classes — annotated

The five domain classes — `@AutoBuilder` `Wallet`, `@EkbatanAction` `WalletDepositAction`, `@EkbatanRepository` `WalletRepository`, `@EkbatanEventHandler` `WalletMoneyDepositedEventHandler`, and `@EkbatanDistributedJob` `DailyWalletReportJob` — are framework-agnostic. **For the full code inline, jump to [annotations.md](annotations.md)** — every line is identical here. Micronaut discovers `@Ekbatan*`-annotated classes via the compile-time `EkbatanStereotypeVisitor` (see [How the integration works](#how-the-integration-works) below); the source itself is unchanged.

### 3. The application bootstrap

Standard Micronaut entry point:

```java
public class WalletsApplication {
    public static void main(String[] args) {
        Micronaut.run(WalletsApplication.class, args);
    }
}
```

### 4. The configuration

`application.yml`:

```yaml
ekbatan:
  namespace: com.example.wallets

  # Optional — opt in to running EventHandlingJob in this process.
  local-event-handler:
    handling:
      enabled: true

  sharding:
    defaultShard:
      group: 0
      member: 0

    groups:
      - group: 0
        name: default
        members:
          - member: 0
            configs:
              primaryConfig:
                jdbcUrl: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 20
                driverClassName: org.postgresql.Driver
              secondaryConfig:
                jdbcUrl: jdbc:postgresql://replica:5432/wallets
                username: wallets_app_ro
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 20
                driverClassName: org.postgresql.Driver
              jobsConfig:
                jdbcUrl: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 5
                driverClassName: org.postgresql.Driver
```

> **`driverClassName` is recommended for Micronaut.** Not every Micronaut/Hikari combination discovers the JDBC `Driver` SPI when the JVM is started by the Gradle test worker or some launcher modes. Setting `driverClassName` makes Hikari `Class.forName(...)` it explicitly.

Make sure `snakeyaml` is on the runtime classpath — Micronaut's `inspectRuntimeClasspath` verifies any `*.yml` has a YAML parser available.

### 5. Use it

```java
@Controller("/wallets")
public class WalletController {

    @Inject ActionExecutor executor;

    @Post("/{id}/deposit")
    public Wallet deposit(UUID id, @Body DepositRequest req) throws Exception {
        return executor.execute(
                () -> "alice",
                WalletDepositAction.class,
                new WalletDepositAction.Params(Id.of(Wallet.class, id), req.amount()));
    }

    public record DepositRequest(BigDecimal amount) {}
}
```

Repositories are also Micronaut beans — inject any `@EkbatanRepository`-annotated class anywhere.

---

## How the integration works

If you only want the tutorial above, stop here. The rest is the reference for what the compile-time visitor + factory beans are doing on your behalf — useful when discovery isn't happening or you need to override a default.

### `EkbatanStereotypeVisitor` (the compile-time machinery)

The integration ships a `TypeElementVisitor<Object, Object>` registered both as:

- `META-INF/services/io.micronaut.inject.visitor.TypeElementVisitor` (legacy SoftServiceLoader format)
- `META-INF/micronaut/io.micronaut.inject.visitor.TypeElementVisitor/io.ekbatan.micronaut.internal.EkbatanStereotypeVisitor` (Micronaut 4.x per-impl marker file)

Both are required — Micronaut 4 reads the per-impl marker, but earlier tooling and incremental-AP builds may still consult the services file.

When the visitor sees a class bearing one of the four `@Ekbatan*` annotations during *your* compile, it calls `element.annotate(Singleton.class)` to lift it to `@Singleton`. Micronaut's annotation processor then generates a `BeanDefinition` for that class. No `@Singleton` annotation in your source — the visitor inserts it.

### Why `annotationProcessor` on the integration jar matters

The visitor only runs on classes being compiled with the visitor JAR on the AP classpath. If your `@Ekbatan*` classes live in a transitive jar that was compiled **without** the visitor, no `BeanDefinition` was ever generated for them, and Micronaut won't find them at runtime — putting the visitor on the downstream module's `annotationProcessor` path doesn't retroactively process already-compiled classes.

Two fixes:

- **Recommended**: ensure the upstream module also includes the visitor on its `annotationProcessor` path, so its `BeanDefinition`s are generated up-front.
- **Alternative**: see the recipe in [`ekbatan-integration-tests/di/shared/build.gradle.kts`](../../ekbatan-integration-tests/di/shared/build.gradle.kts), which pre-generates the BeanDefinitions in the shared jar.

### The three `@Factory` classes

In `io.ekbatan.micronaut`:

- **`EkbatanCoreConfiguration`** — produces `EkbatanConfigJacksonModule`, `ShardingConfig`, `DatabaseRegistry`, `Clock`, `JsonMapper`, `RepositoryRegistry`, `ActionRegistry`, `ActionExecutor`. The executor's factory takes `Optional<EventPersister>`: if the application declares its own `EventPersister` `@Bean`, it replaces the executor's default `SingleTableJsonEventPersister`. Otherwise the default is used — and that default already writes `delivered=false`, so the local-event-handler fan-out picks events up automatically.
- **`EkbatanLocalEventHandlerConfiguration`** — `@Requires(classes = EventHandlerRegistry.class)`. Produces `EventHandlerRegistry`, `EventFanoutJob`, and conditionally `EventHandlingJob` (gated on `@Requires(property = "ekbatan.local-event-handler.handling.enabled", value = "true")`).
- **`EkbatanDistributedJobsConfiguration`** — `@Requires(classes = JobRegistry.class)`. Produces `ConnectionProvider` (from `jobsConfig`) and `JobRegistry`. A nested `Lifecycle` class implements `ApplicationEventListener<StartupEvent>` for start, and a separate `@EventListener void onShutdown(ShutdownEvent)` handles graceful stop.

### The four `@Ekbatan*` annotations

| Annotation | What the integration does |
|---|---|
| `@EkbatanAction` | `EkbatanStereotypeVisitor` lifts to `@Singleton` at *your* compile time → Micronaut generates a `BeanDefinition` → injected as `List<Action<?, ?>>` into `EkbatanCoreConfiguration.ekbatanActionRegistry`. |
| `@EkbatanRepository` | Same — lifted to `@Singleton`, BeanDefinition generated. Injected directly anywhere it's needed and into `RepositoryRegistry` via `List<AbstractRepository>`. |
| `@EkbatanEventHandler` | Same lifting; only effective when the local-event-handler module is on the classpath (`@Requires(classes = ...)`). |
| `@EkbatanDistributedJob` | Same lifting; only effective when ekbatan-distributed-jobs is on the classpath. |

### Native-image specifics

- The GraalVM Build Tools plugin auto-pulls the GraalVM Reachability Metadata Repository, which already covers HikariCP and the major JDBC drivers. Nothing extra to vendor.
- For your own records / `@AutoBuilder` builders / `@JsonCreator` mixins / jOOQ-generated classes that live outside `io.ekbatan`, extend the scan roots:
  ```kotlin
  graalvmNative {
      binaries.all {
          buildArgs.add("-Dio.ekbatan.graalvm.scan.packages=io.ekbatan,com.your.package")
      }
  }
  ```

For broader native-image considerations, see [docs/runtime/native-image.md](../runtime/native-image.md).

### Optional knobs

Same `ekbatan.namespace` / `ekbatan.local-event-handler.*` / `ekbatan.jobs.*` properties as Spring/Quarkus (kebab-case in YAML or camelCase — Micronaut accepts both).

## What's deliberately *not* bridged

- **Micronaut Data** — can coexist with Ekbatan in the same app (different concerns, different datasources or the same one) but the framework does not integrate with Micronaut Data repositories.
- **Micronaut's own transactional annotations** — Ekbatan owns its own `TransactionManager`. Code outside an Action that needs database transactions should use the host framework's facilities directly on its own datasource.

## See also

- [Wiring without DI](without-di.md) — what the visitor + factory beans are doing for you
- [Wiring with Spring Boot](spring.md) / [Wiring with Quarkus](quarkus.md) — same end state in the other DI frameworks
- [Actions, ActionPlan, ActionExecutor](../concepts/actions.md) — what `executor.execute(...)` runs on your behalf
- [Listen-to-yourself: in-process event handlers](../events/local-event-handler.md) — what `@EkbatanEventHandler` consumes
- [Distributed background jobs](../jobs/distributed-jobs.md) — what `@EkbatanDistributedJob` schedules
- [GraalVM native-image](../runtime/native-image.md) — Micronaut + native specifics
- The runnable reference: [`ekbatan-integration-tests/di/micronaut`](../../ekbatan-integration-tests/di/micronaut)
