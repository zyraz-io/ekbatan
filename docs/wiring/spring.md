# Wiring with Spring Boot

What you write in a Spring Boot app to use Ekbatan: one starter dependency, four `@Ekbatan*` annotations on your own classes, and a property tree under `ekbatan.*`. The auto-config produces every framework bean for you and exposes `ActionExecutor` for injection.

For the equivalent in plain Java with no DI container, see [wiring/without-di.md](without-di.md). For Quarkus and Micronaut, see [quarkus.md](quarkus.md) and [micronaut.md](micronaut.md).

## What you write

### 1. The starter dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.zyraz-io:ekbatan-spring-boot-starter:<version>")
}
```

(Published on Maven Central under groupId `io.github.zyraz-io`. Java packages stay `io.ekbatan.*` — they don't need to match the Maven groupId.)

That single dependency transitively pulls in `ekbatan-core`, `ekbatan-events:local-event-handler`, `ekbatan-distributed-jobs`, and the `@Ekbatan*` annotation jar. Add `ekbatan-keyed-lock-redis` separately if you want the Redis-backed lock provider.

> **Custom `EventPersister`** — apps that need to swap the default outbox writer (encrypt payloads, write to a different table, ship to an external sink) can declare their own `EventPersister` `@Bean`; the auto-config picks it up via `ObjectProvider<EventPersister>` and uses it instead of `SingleTableJsonEventPersister`.

### 2. Your domain classes — annotated

The five domain classes — `@AutoBuilder` `Wallet`, `@EkbatanAction` `WalletDepositAction`, `@EkbatanRepository` `WalletRepository`, `@EkbatanEventHandler` `WalletMoneyDepositedEventHandler`, and `@EkbatanDistributedJob` `DailyWalletReportJob` — are framework-agnostic. **For the full code inline, jump to [annotations.md](annotations.md)** — every line is identical here. Spring discovers `@Ekbatan*`-annotated classes via classpath scan + AOT processor (see [How the integration works](#how-the-integration-works) below); the source itself is unchanged.

### 3. The application bootstrap

```java
@SpringBootApplication
public class WalletsApplication {
    public static void main(String[] args) {
        SpringApplication.run(WalletsApplication.class, args);
    }
}
```

That's it. **No `@Configuration`, no `@Bean` factories, no manual registry construction.** The starter ships three `@AutoConfiguration` classes that produce `DatabaseRegistry`, `ActionRegistry`, `RepositoryRegistry`, `EventHandlerRegistry`, `JobRegistry`, `EventPersister`, and `ActionExecutor` from the discovered annotations and the `ekbatan.*` properties.

### 4. The configuration

`application.yml`:

```yaml
ekbatan:
  namespace: com.example.wallets

  # Optional — opt in to running EventHandlingJob in this process.
  # Off by default; enable on at least one node per cluster if you want
  # in-process handlers to actually fire.
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
              secondaryConfig:
                jdbcUrl: jdbc:postgresql://replica:5432/wallets
                username: wallets_app_ro
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 20
              jobsConfig:
                jdbcUrl: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximumPoolSize: 5
```

The `ekbatan.sharding.*` subtree mirrors the structure described in [docs/database/sharding.md](../database/sharding.md). For a single-database deployment, the shape above is all you need.

### 5. Use it

```java
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final ActionExecutor executor;

    public WalletController(ActionExecutor executor) { this.executor = executor; }

    @PostMapping("/{id}/deposit")
    public Wallet deposit(
            @PathVariable UUID id,
            @RequestBody DepositRequest req,
            Principal principal) throws Exception {
        return executor.execute(
                principal,
                WalletDepositAction.class,
                new WalletDepositAction.Params(Id.of(Wallet.class, id), req.amount()));
    }

    public record DepositRequest(BigDecimal amount) {}
}
```

Repositories are also Spring beans — inject any `@EkbatanRepository`-annotated class anywhere and use the inherited CRUD or your own custom queries.

---

## How the integration works

If you only want the tutorial above, stop here. The rest is the reference for what the auto-config is doing on your behalf — useful when something doesn't auto-wire or you need to override a default.

### The three auto-configurations

Listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

- **`EkbatanCoreConfiguration`** — produces `EkbatanConfigJacksonModule`, `ShardingConfig`, `DatabaseRegistry`, `Clock`, `RepositoryRegistry`, `ActionRegistry`, `ActionExecutor`. The executor is built with `ObjectProvider<EventPersister>`: if your application registers its own `EventPersister` bean (e.g. one that encrypts payloads or writes to a separate sink), that bean replaces the executor's default `SingleTableJsonEventPersister`. Otherwise the default is used — and that default already writes `delivered=false`, so the local-event-handler fan-out picks events up automatically.
- **`EkbatanLocalEventHandlerConfiguration`** — `@ConditionalOnClass(EventHandlerRegistry.class)` + `@ConditionalOnBean(EventHandler.class)`. Produces `EventHandlerRegistry`, `EventFanoutJob`, and conditionally `EventHandlingJob` (gated on `ekbatan.local-event-handler.handling.enabled=true`).
- **`EkbatanDistributedJobsConfiguration`** — `@ConditionalOnClass(JobRegistry.class)` + `@ConditionalOnBean(DistributedJob.class)`. Produces `ConnectionProvider` (from `jobsConfig`) and `JobRegistry`. Started with `initMethod="start"` / `destroyMethod="stop"` so Spring manages the lifecycle.

Each auto-config is `@ConditionalOnMissingBean`-gated, so you can override any bean by declaring your own.

### The four `@Ekbatan*` annotations

| Annotation | What the integration does |
|---|---|
| `@EkbatanAction` | Discovered via classpath scan or AOT processor; instantiated as a framework-private singleton (not a Spring bean — see below); registered into `ActionRegistry` so `ActionExecutor.execute(...)` can find it. |
| `@EkbatanRepository` | Registered as a Spring bean by `EkbatanStereotypeBeanRegistrar`; injected into `RepositoryRegistry` keyed by domain class. |
| `@EkbatanEventHandler` | Registered as a Spring bean; added to `EventHandlerRegistry`. Only effective when `ekbatan-events:local-event-handler` is on the classpath. |
| `@EkbatanDistributedJob` | Registered as a Spring bean; added to `JobRegistry`. Only effective when `ekbatan-distributed-jobs` is on the classpath. |

### Why actions aren't Spring beans

Action subclasses must not have mutable instance state — per-call state is bound by the framework via `ScopedValue` (see [docs/concepts/actions.md](../concepts/actions.md)). The `EkbatanCoreConfiguration` resolves the set of `@EkbatanAction` classes (from AOT or runtime scan), instantiates each via `AutowireCapableBeanFactory.createBean(Class)` so constructor injection still works, but registers them only into `ActionRegistry` — not into the Spring bean container. This prevents accidental injection of an `Action` into application code, which would invite the wrong usage pattern.

### AOT / native-image

`EkbatanActionsAotProcessor` (registered via `META-INF/spring/aot.factories`) runs at `processAot` time:

1. Scans for `@EkbatanAction` classes on the AOT classpath.
2. Emits a generated bean factory initializer that populates `EkbatanActionsHolder` with the discovered classes.
3. Registers reflection hints (`INVOKE_DECLARED_CONSTRUCTORS`, `INVOKE_DECLARED_METHODS`, `DECLARED_FIELDS`) so native-image can invoke the action constructors at runtime.

At runtime, `EkbatanCoreConfiguration` first checks `EkbatanActionsHolder.get()` (populated by AOT) and falls back to a JVM classpath scan only if it's empty. So on native, no classpath scanning happens — actions are statically known.

For broader native-image considerations (Jackson 3 record reflection, jOOQ, JDBC drivers, HikariCP), see [docs/runtime/native-image.md](../runtime/native-image.md).

### Optional knobs

| Property | Default | Purpose |
|---|---|---|
| `ekbatan.namespace` | required | Stamped on every `eventlog.events` row; drives Kafka topic naming if you stream events |
| `ekbatan.local-event-handler.handling.enabled` | `false` | Run `EventHandlingJob` in this process. Off by default so deployments with external consumers (Kafka) keep their `@EkbatanEventHandler` beans without an in-process consumer. |
| `ekbatan.local-event-handler.fanout-poll-delay` / `handling-poll-delay` / `fanout-batch-size` / `handling-batch-size` / `handling-max-backoff-cap` / `handling-retention-window` | sensible defaults | Tunables for the fan-out and dispatch jobs — see [docs/events/local-event-handler.md](../events/local-event-handler.md) |
| `ekbatan.jobs.polling-interval` / `heartbeat-interval` / `shutdown-max-wait` | sensible defaults | Tunables for `JobRegistry` — see [docs/jobs/distributed-jobs.md](../jobs/distributed-jobs.md) |

## What's deliberately *not* bridged

- **Spring's `@Transactional` / `PlatformTransactionManager`** — Ekbatan owns its own `TransactionManager`. Code outside an Action that needs database transactions should use the host framework's facilities directly on its own datasource. The action boundary *is* the transaction boundary.
- **Spring Data JPA** — can coexist with Ekbatan in the same app (different concerns, different or shared datasources) but the framework does not integrate with `JpaRepository` / `CrudRepository`.

## See also

- [Wiring without DI](without-di.md) — what's hiding behind the auto-config
- [Wiring with Quarkus](quarkus.md) / [Wiring with Micronaut](micronaut.md) — same end state in the other DI frameworks
- [Actions, ActionPlan, ActionExecutor](../concepts/actions.md) — what `executor.execute(...)` runs on your behalf
- [Listen-to-yourself: in-process event handlers](../events/local-event-handler.md) — what `@EkbatanEventHandler` consumes
- [Distributed background jobs](../jobs/distributed-jobs.md) — what `@EkbatanDistributedJob` schedules
- [GraalVM native-image](../runtime/native-image.md) — Spring AOT specifics
- Runnable references:
  - [`ekbatan-integration-tests/di/spring-boot-starter`](../../ekbatan-integration-tests/di/spring-boot-starter) — the framework's own smoke test for the Spring Boot integration.
  - [`ekbatan-examples/spring-boot-wallet-rest`](../../ekbatan-examples/spring-boot-wallet-rest) — a standalone Spring Boot app that uses Ekbatan as a Maven Central dependency, with a Wallet `Model`, a Notification `Entity`, three Actions, an `EventHandler` that runs listen-to-yourself, REST endpoints, and a Testcontainers integration test. Closer to what you'd actually write in your own service.
