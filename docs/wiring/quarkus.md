# Wiring with Quarkus

What you write in a Quarkus app to use Ekbatan: the extension dependency, four `@Ekbatan*` annotations on your own classes, and an `application.properties` tree under `ekbatan.*`. The extension's deployment-time build steps wire every framework bean, and `ActionExecutor` is injectable anywhere.

For the equivalent in plain Java with no DI container, see [wiring/without-di.md](without-di.md). For Spring Boot and Micronaut, see [spring.md](spring.md) and [micronaut.md](micronaut.md).

## What you write

### 1. The extension dependency

> ⚠️ **Coming soon** — Maven Central coordinates land once the framework is published. For now, `./gradlew publishToMavenLocal` and consume from your local cache.

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.ekbatan:ekbatan-di-quarkus:<version>")
}
```

That one runtime artifact transitively pulls in `ekbatan-core`, `ekbatan-events:local-event-handler`, `ekbatan-distributed-jobs`, and the `@Ekbatan*` annotation jar. Quarkus resolves the matching deployment module automatically at build time. Add `ekbatan-keyed-lock-redis` separately if you want the Redis-backed lock provider.

### 2. Your domain classes — annotated

The five domain classes — `@AutoBuilder` `Wallet`, `@EkbatanAction` `WalletDepositAction`, `@EkbatanRepository` `WalletRepository`, `@EkbatanEventHandler` `WalletMoneyDepositedEventHandler`, and `@EkbatanDistributedJob` `DailyWalletReportJob` — are framework-agnostic. **For the full code inline, jump to [annotations.md](annotations.md)** — every line is identical here. Quarkus discovers `@Ekbatan*`-annotated classes via Jandex at deployment time (see [How the extension works](#how-the-extension-works) below); the source itself is unchanged.

### 3. The application bootstrap

Just a Quarkus app. Nothing Ekbatan-specific.

```java
@QuarkusMain
public class WalletsApplication {
    public static void main(String[] args) {
        Quarkus.run(args);
    }
}
```

(Or if you have a `@Path`-annotated REST resource you don't even need a `main`; Quarkus assembles the application around it.)

### 4. The configuration

`application.properties`:

```properties
ekbatan.namespace=com.example.wallets

# Build-time gate for EventHandlingJob — see "Build-time vs runtime" below.
ekbatan.local-event-handler.handling.enabled=true

# Sharding — single shard pointing at PG.
ekbatan.sharding.defaultShard.group=0
ekbatan.sharding.defaultShard.member=0
ekbatan.sharding.groups[0].group=0
ekbatan.sharding.groups[0].name=default
ekbatan.sharding.groups[0].members[0].member=0
ekbatan.sharding.groups[0].members[0].configs.primaryConfig.jdbcUrl=jdbc:postgresql://primary:5432/wallets
ekbatan.sharding.groups[0].members[0].configs.primaryConfig.username=wallets_app
ekbatan.sharding.groups[0].members[0].configs.primaryConfig.password=${APP_DB_PASSWORD}
ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=20
ekbatan.sharding.groups[0].members[0].configs.primaryConfig.driverClassName=org.postgresql.Driver

ekbatan.sharding.groups[0].members[0].configs.secondaryConfig.jdbcUrl=jdbc:postgresql://replica:5432/wallets
ekbatan.sharding.groups[0].members[0].configs.secondaryConfig.username=wallets_app_ro
ekbatan.sharding.groups[0].members[0].configs.secondaryConfig.password=${APP_DB_PASSWORD}
ekbatan.sharding.groups[0].members[0].configs.secondaryConfig.maximumPoolSize=20
ekbatan.sharding.groups[0].members[0].configs.secondaryConfig.driverClassName=org.postgresql.Driver

ekbatan.sharding.groups[0].members[0].configs.jobsConfig.jdbcUrl=jdbc:postgresql://primary:5432/wallets
ekbatan.sharding.groups[0].members[0].configs.jobsConfig.username=wallets_app
ekbatan.sharding.groups[0].members[0].configs.jobsConfig.password=${APP_DB_PASSWORD}
ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=5
ekbatan.sharding.groups[0].members[0].configs.jobsConfig.driverClassName=org.postgresql.Driver
```

> **`driverClassName` is explicitly required for Quarkus.** SmallRye Config + the Quarkus runtime classloader don't always discover the JDBC `Driver` SPI during the Arc producer phase the way a vanilla JVM's `DriverManager` does. Setting `driverClassName` makes Hikari `Class.forName(...)` the driver explicitly. (Spring Boot and Micronaut don't usually need this.)

The `ekbatan.sharding.*` subtree mirrors the structure described in [docs/database/sharding.md](../database/sharding.md).

### 5. Use it

```java
@Path("/wallets")
public class WalletResource {

    @Inject ActionExecutor executor;

    @POST
    @Path("/{id}/deposit")
    public Wallet deposit(@PathParam("id") UUID id, DepositRequest req) throws Exception {
        return executor.execute(
                () -> "alice",
                WalletDepositAction.class,
                new WalletDepositAction.Params(Id.of(Wallet.class, id), req.amount()));
    }

    public record DepositRequest(BigDecimal amount) {}
}
```

Repositories are also CDI beans — inject any `@EkbatanRepository`-annotated class anywhere.

---

## How the extension works

If you only want the tutorial above, stop here. The rest is the reference for what the deployment module is doing on your behalf — useful when something doesn't auto-discover or you need to override a default.

### Runtime + deployment split

Quarkus extensions are two modules: `runtime` (loaded into your app classpath) and `deployment` (only used during the Quarkus build). Ekbatan's split:

- **Runtime** — the three `Configuration` `@Singleton` factory classes that produce the framework's beans (`ActionExecutor`, `DatabaseRegistry`, `EventHandlerRegistry`, `JobRegistry`, etc.) using CDI's `@Produces`. The `ActionExecutor` producer takes `Instance<EventPersister>`: if the application defines its own `@Produces EventPersister`, it replaces the executor's default `SingleTableJsonEventPersister`. Otherwise the default is used — and that default already writes `delivered=false`, so the local-event-handler fan-out picks events up automatically.
- **Deployment** — `EkbatanProcessor` with several `@BuildStep` methods that:
  1. Add the `Configuration` classes themselves as unremovable Arc beans (string class names, not `Class<?>`, to avoid loading runtime types into the deployment classloader).
  2. Register `EkbatanProperties` as a `ConfigMappingBuildItem`.
  3. Disable strict SmallRye mapping validation (the `ekbatan.sharding.*` subtree is bound by Jackson, not by `@ConfigMapping`, so SmallRye must not reject those keys).
  4. Walk the Jandex combined index for `@EkbatanAction` / `@EkbatanRepository` / `@EkbatanEventHandler` / `@EkbatanDistributedJob`, collect their class names, and register each as an unremovable singleton.
  5. Conditionally register the local-event-handler / distributed-jobs producer classes only if their corresponding modules are present at runtime — checked via `QuarkusClassLoader.isClassPresentAtRuntime(...)`, **never** `Class.forName`.

That last point is critical: `Class.forName` would load a runtime class into the deployment classloader, which then conflicts with the runtime classloader at boot. `QuarkusClassLoader.isClassPresentAtRuntime` returns a boolean without loading anything.

### The four `@Ekbatan*` annotations

Same set as Spring/Micronaut. Discovery mechanism differs:

| Annotation | Quarkus discovery |
|---|---|
| `@EkbatanAction` | Found in the Jandex index by `discoverEkbatanBeans` build step → registered as `@Singleton @Unremovable` Arc bean → injected via `@All List<Action<?, ?>>` into `EkbatanCoreConfiguration.ekbatanActionRegistry`. |
| `@EkbatanRepository` | Same — Jandex → unremovable Arc singleton. Injected directly anywhere it's needed and into `RepositoryRegistry` via `@All List<AbstractRepository>`. |
| `@EkbatanEventHandler` | Same path; only effective when the local-event-handler module is on the classpath. |
| `@EkbatanDistributedJob` | Same path; only effective when ekbatan-distributed-jobs is on the classpath. |

### Indexing transitive jars

If your `@Ekbatan*` classes live in a transitive jar (e.g. a shared module across multiple Quarkus apps), Jandex won't see them by default. Tell Quarkus to walk that jar's index too:

```properties
quarkus.index-dependency.<alias>.group-id=com.your.group
quarkus.index-dependency.<alias>.artifact-id=your-shared-artifact
```

Without this, the deployment processor's scan returns nothing for that jar and Arc fails at boot with `UnsatisfiedResolutionException` for the missing repository / handler / job.

### Build-time vs runtime gates

`@IfBuildProperty(name = "ekbatan.local-event-handler.handling.enabled", stringValue = "true", enableIfMissing = false)` on `EkbatanLocalEventHandlerConfiguration.ekbatanEventHandlingJob` is evaluated **at jar assembly time**, not at runtime. Set the flag in `application.properties`; runtime overrides won't flip it.

This matches Spring's `@ConditionalOnProperty(havingValue="true")` "default-off" semantic. The reason it's build-time-only on Quarkus is so the `EventHandlingJob` bean simply doesn't exist in the closed-world image — useful for native builds where every bean adds reflection metadata.

### Native-image specifics

- **JDBC drivers** come via `quarkus-jdbc-postgresql` / `quarkus-jdbc-mysql` / `quarkus-jdbc-mariadb`. Add the matching extension to your build; Quarkus registers the driver class for native automatically.
- **HikariCP** is *not* covered by any Quarkus extension (Quarkus blesses Agroal). The framework integration tests vendor the upstream GraalVM Reachability Metadata Repository entry at [`ekbatan-integration-tests/di/quarkus/src/main/resources/META-INF/native-image/com.zaxxer/HikariCP/reachability-metadata.json`](../../ekbatan-integration-tests/di/quarkus). Copy that file into your app's `META-INF/native-image/com.zaxxer/HikariCP/`.
- **Jackson 3 records** — the `ekbatan-native` module's `Jackson3RecordsFeature` picks up the framework's records automatically. For your own records / `@AutoBuilder` builders, set `quarkus.native.additional-build-args=-Dio.ekbatan.graalvm.scan.packages=io.ekbatan\,com.your.package` (the comma in the value must be escaped — Quarkus uses `,` to separate multiple build args).

For broader native-image considerations, see [docs/runtime/native-image.md](../runtime/native-image.md).

### Optional knobs

Same `ekbatan.namespace` / `ekbatan.local-event-handler.*` / `ekbatan.jobs.*` properties as Spring (with kebab-case in `application.properties`):

```properties
ekbatan.local-event-handler.fanout-poll-delay=200ms
ekbatan.local-event-handler.handling-poll-delay=200ms
ekbatan.jobs.polling-interval=1s
ekbatan.jobs.shutdown-max-wait=5s
```

## What's deliberately *not* bridged

- **Quarkus' Agroal datasource** — Ekbatan uses HikariCP via its own `ConnectionProvider`. Don't try to pass an Agroal datasource into Ekbatan; declare a separate sharding config block instead.
- **Quarkus Hibernate Panache** — can coexist with Ekbatan in the same app (different concerns, different datasources or even the same one) but the framework does not integrate with Panache repositories.

## See also

- [Wiring without DI](without-di.md) — what the deployment build steps + runtime producers are doing for you
- [Wiring with Spring Boot](spring.md) / [Wiring with Micronaut](micronaut.md) — same end state in the other DI frameworks
- [Actions, ActionPlan, ActionExecutor](../concepts/actions.md) — what `executor.execute(...)` runs on your behalf
- [Listen-to-yourself: in-process event handlers](../events/local-event-handler.md) — what `@EkbatanEventHandler` consumes
- [Distributed background jobs](../jobs/distributed-jobs.md) — what `@EkbatanDistributedJob` schedules
- [GraalVM native-image](../runtime/native-image.md) — Quarkus + native specifics
- The runnable reference: [`ekbatan-integration-tests/di/quarkus`](../../ekbatan-integration-tests/di/quarkus)
