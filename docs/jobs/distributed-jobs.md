# Distributed background jobs

For periodic background work that should run on **at most one** instance across a cluster — daily reports, hourly cleanups, periodic reconciliation — Ekbatan ships `JobRegistry` in the `ekbatan-distributed-jobs` module. It's a thin, opinionated facade over [db-scheduler](https://github.com/kagkarlsson/db-scheduler) that handles the tricky parts (atomic claim across instances, heartbeat-based crash recovery, graceful shutdown, per-task virtual-thread workers) while keeping the user-facing API tiny.

## Defining a job

Extend `DistributedJob`:

```java
@EkbatanDistributedJob
public class DailyReportJob extends DistributedJob {

    private final ReportService reportService;

    public DailyReportJob(ReportService reportService) {
        this.reportService = reportService;
    }

    @Override public String name() {
        return "daily-report";        // cluster-wide unique
    }

    @Override public Schedule schedule() {
        return Schedules.daily(LocalTime.of(2, 0));
    }

    @Override public void execute(ExecutionContext ctx) {
        reportService.generateAndSend();
    }
}
```

`Schedule` is db-scheduler's interface, so any of its implementations work directly: `FixedDelay`, `FixedRate`, `Cron`, `Daily`, etc.

The `@EkbatanDistributedJob` annotation marks the class for discovery by the DI integrations (Spring Boot, Quarkus, Micronaut). Without DI, register the job manually — see [Wiring without DI](#wiring-without-di) below.

## Coordination semantics (inherited from db-scheduler)

- Every instance polls the shared `scheduled_tasks` table.
- When a task's `execution_time` arrives, exactly one instance wins the atomic claim per scheduled slot — that instance runs it.
- Each running instance heartbeats periodically. If a heartbeat goes stale (instance crashed, network partitioned), another instance reclaims the row.
- Tasks that throw are retried per their `Schedule`; the row's `consecutive_failures` increments.

This is at-most-one *per scheduled slot*, not at-most-one ever. A daily-at-02:00 job runs once at 02:00 across the cluster; if 02:00 passes while every node is down, the missed slot is picked up by the next live node when it polls.

## The `scheduled_tasks` table

The module needs db-scheduler's table provisioned in your application's database. Verbatim PostgreSQL schema:

```sql
CREATE TABLE scheduled_tasks (
    task_name            TEXT     NOT NULL,
    task_instance        TEXT     NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN  NOT NULL,
    picked_by            TEXT,
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT   NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx        ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx        ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
```

For the MySQL/MariaDB equivalents, see db-scheduler's [`postgresql_tables.sql`](https://github.com/kagkarlsson/db-scheduler/tree/master/db-scheduler/src/main/resources) and [`mysql_tables.sql`](https://github.com/kagkarlsson/db-scheduler/tree/master/db-scheduler/src/main/resources). The `TIMESTAMP WITH TIME ZONE` column is db-scheduler's choice (this is the one place the framework deliberately steps off the always-`TIMESTAMP` rule — db-scheduler owns the table and its schema).

A reference migration lives in [`ekbatan-integration-tests/distributed-jobs-pg/src/test/resources/db/migration/V0001__create_scheduled_tasks.sql`](../../ekbatan-integration-tests/distributed-jobs-pg/src/test/resources/db/migration/V0001__create_scheduled_tasks.sql).

## Wiring without DI

`JobRegistry` is a builder facade over a single db-scheduler `Scheduler`:

```java
import static io.ekbatan.distributedjobs.JobRegistry.jobRegistry;
import static io.ekbatan.core.persistence.ConnectionProvider.hikariConnectionProvider;

var jobsPool = hikariConnectionProvider(jobsDataSourceConfig);

var registry = jobRegistry()
        .connectionProvider(jobsPool)
        .withJob(new DailyReportJob(reportService))
        .withJob(new HourlyCleanupJob(cleanupService))
        .pollInterval(Duration.ofSeconds(10))
        .heartbeatInterval(Duration.ofSeconds(30))
        .shutdownMaxWait(Duration.ofSeconds(30))
        .build();   // a JVM shutdown hook is installed by default

registry.start();
```

For advanced db-scheduler settings not exposed by the builder (`missedHeartbeatsLimit`, `deleteUnresolvedAfter`, custom polling strategy, etc.), `customizeScheduler(...)` runs last in `build()` and can override any of Ekbatan's defaults:

```java
var registry = jobRegistry()
        .connectionProvider(jobsPool)
        .withJob(new DailyReportJob(reportService))
        .customizeScheduler(b -> b
                .missedHeartbeatsLimit(3)
                .deleteUnresolvedAfter(Duration.ofDays(30)))
        .build();
```

`JobRegistry` auto-sizes `threads(jobs.size())` for the polling batch and swaps in `Executors.newVirtualThreadPerTaskExecutor()` for workers, so per-job concurrency is governed by virtual-thread scheduling rather than a fixed thread pool.

## Wiring via DI

With the `@EkbatanDistributedJob` annotation in place, the DI integration registers each `DistributedJob` bean as a managed singleton and adds it to a `JobRegistry` configured from `application.yml`:

```yaml
ekbatan:
  jobs:
    polling-interval: 10s
    heartbeat-interval: 30s
    shutdown-max-wait: 30s

  sharding:
    groups:
      - members:
          - configs:
              primaryConfig: { … }
              jobsConfig:                 # dedicated pool for the scheduler — see next section
                jdbcUrl: jdbc:postgresql://primary:5432/app
                username: app
                password: ${APP_PASSWORD}
                maximumPoolSize: 5
```

`JobRegistry.start()` is wired to your DI container's lifecycle (Spring `initMethod`/`destroyMethod`, Quarkus `@Observes StartupEvent`/`ShutdownEvent`, Micronaut `ApplicationEventListener<StartupEvent>`).

## Use a dedicated connection pool

Use a dedicated `ConnectionProvider` for `JobRegistry` — separate from your primary application pool. db-scheduler polls continuously, so you don't want it competing with normal queries for connections. A small pool is enough (polling + heartbeats are low-volume).

The DI integrations expect this pool under the user-defined `jobsConfig` slot of the default shard's first member, as shown above. Manual wiring uses `member.configFor("jobsConfig")` the same way.

## See also

- [Wiring with Spring Boot](../wiring/spring.md) / [Quarkus](../wiring/quarkus.md) / [Micronaut](../wiring/micronaut.md) — `@EkbatanDistributedJob` discovery
- [Sharding](../database/sharding.md) — where the `jobsConfig` slot lives
- [Listen-to-yourself: in-process event handlers](../events/local-event-handler.md) — both `EventFanoutJob` and `EventHandlingJob` are themselves `DistributedJob` instances registered with the same `JobRegistry`
