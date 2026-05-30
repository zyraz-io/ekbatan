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

This is what you actually write. Five domain classes carry five annotations — `@AutoBuilder` on the `Model`, and the four `@Ekbatan*` markers on the action, repository, event handler, and job. They're framework-agnostic: the same source compiles and runs identically against Spring Boot, Quarkus, and Micronaut. The four `@Ekbatan*` annotations are pure markers; `@AutoBuilder` is an independent compile-time builder generator. Spring discovers the `@Ekbatan*`-annotated classes via classpath scan + AOT processor (see [How the integration works](#how-the-integration-works) below); the source itself is unchanged.

#### Wallet — the Model (`@AutoBuilder`)

```java
@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId  = Validate.notNull(builder.ownerId,  "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance  = Validate.notNull(builder.balance,  "balance cannot be null");
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");
        final var newBalance = balance.add(amount);
        return copy()
                .withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet().copyBase(this).ownerId(ownerId).currency(currency).balance(balance);
    }
}
```

#### WalletDepositAction — the Action (`@EkbatanAction`)

Discovered and registered into `ActionRegistry` so `ActionExecutor.execute(...)` can find it. Constructor params are resolved by the DI container.

```java
@EkbatanAction
public class WalletDepositAction extends Action<WalletDepositAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public WalletDepositAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        var wallet  = walletRepository.getById(params.walletId().getValue());
        var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
```

#### WalletRepository — the Repository (`@EkbatanRepository`)

Registered as a managed DI bean and into `RepositoryRegistry`. Inject it by its concrete class anywhere.

```java
@EkbatanRepository
public class WalletRepository extends ModelRepository<Wallet, WalletsRecord, Wallets, UUID> {

    public WalletRepository(DatabaseRegistry databaseRegistry) {
        super(Wallet.class, WALLETS, WALLETS.ID, databaseRegistry);
    }

    @Override
    public Wallet fromRecord(WalletsRecord r) {
        return WalletBuilder.wallet()
                .id(Id.of(Wallet.class, r.getId()))
                .version(r.getVersion())
                .state(WalletState.valueOf(r.getState()))
                .ownerId(r.getOwnerId())
                .currency(Currency.getInstance(r.getCurrency()))
                .balance(r.getBalance())
                .createdDate(r.getCreatedDate())
                .updatedDate(r.getUpdatedDate())
                .build();
    }

    @Override
    public WalletsRecord toRecord(Wallet w) {
        return new WalletsRecord(
                w.id.getValue(), w.version, w.state.name(),
                w.ownerId, w.currency.getCurrencyCode(), w.balance,
                w.createdDate, w.updatedDate);
    }
}
```

#### WalletMoneyDepositedEventHandler — the EventHandler (`@EkbatanEventHandler`)

Registered with `EventHandlerRegistry`. Only effective when the local-event-handler module is on the classpath.

```java
@EkbatanEventHandler
public class WalletMoneyDepositedEventHandler implements EventHandler<WalletMoneyDepositedEvent> {

    private final NotificationService notificationService;

    public WalletMoneyDepositedEventHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override public String name()                               { return "wallet-deposit-notification"; }
    @Override public Class<WalletMoneyDepositedEvent> eventType() { return WalletMoneyDepositedEvent.class; }

    @Override
    public void handle(EventEnvelope<WalletMoneyDepositedEvent> envelope) {
        notificationService.notifyDeposit(envelope.event.modelId, envelope.event.amount);
    }
}
```

#### DailyWalletReportJob — the DistributedJob (`@EkbatanDistributedJob`)

Registered with `JobRegistry`. Only effective when the distributed-jobs module is on the classpath.

```java
@EkbatanDistributedJob
public class DailyWalletReportJob extends DistributedJob {

    private final ReportService reportService;

    public DailyWalletReportJob(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override public String name()       { return "daily-wallet-report"; }
    @Override public Schedule schedule() { return Schedules.daily(LocalTime.of(2, 0)); }

    @Override
    public void execute(ExecutionContext ctx) {
        reportService.generateAndSend();
    }
}
```

For the annotation reference table and the full rationale on why `Action` instances are *not* exposed as DI beans, see [annotations.md](annotations.md).

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
    default-shard:
      group: 0
      member: 0

    groups:
      - group: 0
        name: default
        members:
          - member: 0
            configs:
              primary-config:
                jdbc-url: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximum-pool-size: 20
              secondary-config:
                jdbc-url: jdbc:postgresql://replica:5432/wallets
                username: wallets_app_ro
                password: ${APP_DB_PASSWORD}
                maximum-pool-size: 20
              jobs-config:
                jdbc-url: jdbc:postgresql://primary:5432/wallets
                username: wallets_app
                password: ${APP_DB_PASSWORD}
                maximum-pool-size: 5
```

The `ekbatan.sharding.*` subtree mirrors the structure described in [docs/database/sharding.md](../database/sharding.md). For a single-database deployment, the shape above is all you need.
Both kebab-case and camelCase config keys are accepted; the starter normalizes keys before binding them to Ekbatan's typed config classes. That includes `jobs-config` / `jobsConfig`, `lock-config` / `lockConfig`, and datasource leaves like `jdbc-url` / `jdbcUrl`. If application code reads an extra datasource from `ShardMemberConfig.configFor(...)`, pass the camelCase key (`configFor("jobsConfig")`, `configFor("lockConfig")`), not the kebab-case spelling.

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

- **`EkbatanCoreConfiguration`** — produces `ShardingConfig`, `JobsConfig`, `LocalEventHandlerConfig`, `DatabaseRegistry`, `Clock`, `RepositoryRegistry`, `ActionRegistry`, `ActionExecutor`. The executor is built with `ObjectProvider<EventPersister>`: if your application registers its own `EventPersister` bean (e.g. one that encrypts payloads or writes to a separate sink), that bean replaces the executor's default `SingleTableJsonEventPersister`. Otherwise the default is used — and that default already writes `delivered=false`, so the local-event-handler fan-out picks events up automatically.
- **`EkbatanLocalEventHandlerConfiguration`** — `@ConditionalOnClass(EventHandlerRegistry.class)` + `@ConditionalOnBean(EventHandler.class)`. Produces `EventHandlerRegistry`, `EventFanoutJob`, and conditionally `EventHandlingJob` (gated on `ekbatan.local-event-handler.handling.enabled=true`).
- **`EkbatanDistributedJobsConfiguration`** — `@ConditionalOnClass(JobRegistry.class)` + `@ConditionalOnBean(DistributedJob.class)`. Produces `ConnectionProvider` (from the `jobs-config` / `jobsConfig` slot) and `JobRegistry`. Started with `initMethod="start"` / `destroyMethod="stop"` so Spring manages the lifecycle.

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

### Jackson — comes via `spring-boot-starter-web`

**Dependencies** — Jackson is pulled by the web starter; you don't need to declare it separately:

```kotlin
// build.gradle.kts
dependencies {
    // ✅ The web starter. Pulls jackson-databind + jackson-datatype-jsr310 + the
    //    MappingJackson2HttpMessageConverter that wires Jackson into request/response
    //    (de)serialization. Reads Jackson config from `spring.jackson.*` properties.
    implementation("org.springframework.boot:spring-boot-starter-web")

    // ❌ Don't add `com.fasterxml.jackson.core:jackson-databind` directly. The web starter
    //    brings the right version pinned by Spring Boot's BOM; pulling jackson-databind
    //    independently can drift from what spring-boot-starter-test, spring-cloud-*, etc.
    //    expect for jackson-core / jackson-annotations.
}
```

To customize the `ObjectMapper`, declare a `@Bean Jackson2ObjectMapperBuilderCustomizer` (Spring's idiomatic hook). The wallet examples don't need any customization — defaults are fine.

> **Ekbatan internals use Jackson 3** (`tools.jackson.databind.*`) for event serialization, not Jackson 2. That dependency is pulled transitively by `ekbatan-core` and is unrelated to your HTTP-layer Jackson 2 setup; the two coexist. `ekbatan-native`'s `Jackson3RecordsFeature` registers your records under Jackson 3 — see [docs/runtime/native-image.md](../runtime/native-image.md).

### Flyway — use `spring-boot-starter-flyway` + a `@FlywayDataSource @Bean`

Don't run Flyway by hand from an `@PostConstruct` bean (that's what the framework's own tests at `ekbatan-integration-tests/di/spring-boot-starter/TestcontainersConfiguration` do via `FlywayHelper`, because they use raw Flyway with no framework extension wrapping it — see [GraalVM native-image § two patterns](../runtime/native-image.md#flyway-on-native--two-patterns)). For a real Spring Boot app, **use `spring-boot-starter-flyway`** and supply a `@FlywayDataSource`-scoped `DataSource` bean built from `ekbatan.sharding.*`.

**Dependencies** — pull the Spring Boot starter, NOT raw `flyway-core`:

```kotlin
// build.gradle.kts
dependencies {
    // ✅ The Spring Boot starter. Pulls flyway-core + spring-boot-flyway (which contains
    //    FlywayAutoConfiguration) transitively at the BOM-pinned version. Spring Boot 4
    //    modularized FlywayAutoConfiguration into its own artifact; the starter brings the
    //    right combination automatically.
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    // ✅ Database-specific Flyway plugin (BOM-managed; no version needed).
    implementation("org.flywaydb:flyway-database-postgresql")   // or flyway-mysql for MariaDB/MySQL

    // ❌ Don't add `org.flywaydb:flyway-core` directly. The starter brings the right version
    //    AND the autoconfig that creates the Flyway bean. Pulling flyway-core alone skips
    //    the autoconfig, leaving you to wire everything by hand.
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

Bean wiring (the `DataSource` is the part that's framework-specific to Spring Boot):

```java
@Configuration
public class EkbatanShardFlywayDataSource {

    @Bean
    @FlywayDataSource    // org.springframework.boot.flyway.autoconfigure.FlywayDataSource (Spring Boot 4)
    public DataSource flywayDataSource(ShardingConfig shardingConfig) {
        var primary = shardingConfig.groups.getFirst().members.getFirst().primaryConfig();
        var ds = new HikariDataSource();
        ds.setJdbcUrl(primary.jdbcUrl);
        ds.setUsername(primary.username);
        ds.setPassword(primary.password);
        ds.setMaximumPoolSize(2);                      // small pool — only Flyway migration uses it
        ds.setPoolName("flyway-migrations");
        return ds;
    }
}
```

Why a `@Bean` (Spring) rather than a `FlywayConfigurationCustomizer` (Quarkus/Micronaut pattern): Spring Boot's `FlywayAutoConfiguration` is gated on `@ConditionalOnBean(DataSource.class)` — without a `DataSource` bean, it never creates the Flyway instance and a customizer would never fire. Producing the `DataSource` programmatically from `ShardingConfig` satisfies the gate AND provides the right coordinates in one step. The `@FlywayDataSource` annotation scopes the bean to Flyway only — Spring Boot won't pick it up as the application's main `DataSource`. Ekbatan keeps owning the application's runtime pools (sharding-aware `ConnectionProvider`); this small pool exists solely for the Flyway migration burst on startup.

The `flywayInitializer` bean (Spring Boot's migration runner) is created during context refresh, before `ekbatanJobRegistry` starts polling — so the schema exists by the time db-scheduler reads `scheduled_tasks`. If you want an explicit happens-before edge anyway:

```java
@Configuration
public class JobRegistryDependsOnFlywayPostProcessor {
    @Bean
    public static BeanFactoryPostProcessor jobRegistryDependsOnFlyway() {
        return beanFactory -> {
            if (!beanFactory.containsBeanDefinition("ekbatanJobRegistry")) return;
            var bd = beanFactory.getBeanDefinition("ekbatanJobRegistry");
            var existing = bd.getDependsOn();
            bd.setDependsOn(existing == null
                    ? new String[]{"flywayInitializer"}
                    : Stream.concat(Arrays.stream(existing), Stream.of("flywayInitializer"))
                            .toArray(String[]::new));
        };
    }
}
```

This is what the wallet examples do as belt-and-braces.

#### Multi-shard: `spring.flyway.enabled=false` + programmatic loop

Spring Boot's auto-config can only attach Flyway to one `DataSource` bean. For multi-shard wallets, disable the auto-config and run the loop yourself:

```yaml
# application.yml
spring:
  flyway:
    enabled: false
```

```java
@Configuration
public class EkbatanShardFlywayMigrator {
    @Bean
    public FlywayMigration flywayMigration(ShardingConfig shardingConfig) {
        for (var group : shardingConfig.groups) {
            for (var member : group.members) {
                var primary = member.primaryConfig();
                Flyway.configure()
                        .dataSource(primary.jdbcUrl, primary.username, primary.password)
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
            }
        }
        return new FlywayMigration();
    }
    public static final class FlywayMigration {}
}
```

See [`ekbatan-examples/spring-boot-wallet-rest-gradle-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-pg) for the single-shard shape and [`spring-boot-wallet-rest-gradle-sharded-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-sharded-pg) for the multi-shard one.

### Optional knobs

| Property | Default | Purpose |
|---|---|---|
| `ekbatan.namespace` | required | Stamped on every `eventlog.events` row; drives Kafka topic naming if you stream events |
| `ekbatan.local-event-handler.handling.enabled` | `false` | Run `EventHandlingJob` in this process. Off by default so deployments with external consumers (Kafka) keep their `@EkbatanEventHandler` beans without an in-process consumer. |
| `ekbatan.local-event-handler.fanout-poll-delay` / `handling-poll-delay` / `fanout-batch-size` / `handling-batch-size` / `handling-max-backoff-cap` / `handling-retention-window` | sensible defaults | Tunables for the fan-out and dispatch jobs — see [docs/events/local-event-handler.md](../events/local-event-handler.md) |
| `ekbatan.jobs.polling-interval` / `heartbeat-interval` / `shutdown-max-wait` | sensible defaults | Tunables for `JobRegistry` — see [docs/jobs/distributed-jobs.md](../jobs/distributed-jobs.md) |

All rows above also accept camelCase aliases: `ekbatan.localEventHandler.*`, `fanoutPollDelay`, `handlingPollDelay`, `handlingMaxBackoffCap`, `handlingRetentionWindow`, `ekbatan.jobs.pollingInterval`, `heartbeatInterval`, and `shutdownMaxWait`.

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
  - [`ekbatan-examples/spring-boot-wallet-rest-gradle-pg`](../../ekbatan-examples/spring-boot-wallet-rest-gradle-pg) — a standalone Spring Boot app that uses Ekbatan as a Maven Central dependency, with a Wallet `Model`, a Notification `Entity`, three Actions, an `EventHandler` that runs listen-to-yourself, REST endpoints, and a Testcontainers integration test. Closer to what you'd actually write in your own service.
