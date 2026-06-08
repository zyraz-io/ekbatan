# Framework tables

Ekbatan applications usually have two kinds of tables:

- **Application tables** that you own, such as `wallets`, `orders`, or `notifications`.
- **Framework tables** that Ekbatan or one of its runtime helpers reads and writes.

Use this page as the table map. The individual table pages contain the full DDL for PostgreSQL, MariaDB, and MySQL, plus column-by-column explanations.

| Table | Required when | Writer | Row representation |
|---|---|---|---|
| [`eventlog.events`](tables/events.md) | You use the default `SingleTableJsonEventPersister`. This is the normal Ekbatan setup. | `ActionExecutor` through `SingleTableJsonEventPersister` | `EventEntity` |
| [`eventlog.event_notifications`](tables/event-notifications.md) | Optional. Only when you use `ekbatan-local-event-handler`, either to handle events locally or to publish from a local handler. | `EventFanoutJob` creates rows; `EventHandlingJob` updates rows. | `EventNotification` |
| [`scheduled_tasks`](tables/scheduled-tasks.md) | Optional. Only when you use `ekbatan-distributed-jobs`, or any module built on it such as `ekbatan-local-event-handler`. | db-scheduler, through Ekbatan's `JobRegistry` facade. | No Ekbatan model; db-scheduler owns this table. |

On PostgreSQL, `eventlog` is a schema inside the application database. On MySQL and MariaDB, `eventlog` is a separate database because those engines treat "schema" and "database" as the same namespace. `scheduled_tasks` lives in the application database, not under `eventlog`.

In a sharded application, each physical shard gets its own copy of these tables. `FlywayMigrator.migrate(shardingConfig)` applies the same migrations to every configured shard.

## Do I need each table?

| Use case | `eventlog.events` | `eventlog.event_notifications` | `scheduled_tasks` |
|---|---:|---:|---:|
| Default Ekbatan action persistence only | Yes | No | No |
| Debezium or CDC reading committed events | Yes | No | No |
| Local event handler fan-out and handling | Yes | Yes | Yes |
| Distributed jobs only | No | No | Yes |
| Custom `EventPersister` that does not use the default table | Depends on your implementation | No, unless you also use local handlers | Depends on whether you use distributed jobs |

## Table pages

- [`eventlog.events`](tables/events.md) — committed action/event rows written by the default event persister.
- [`eventlog.event_notifications`](tables/event-notifications.md) — per-handler delivery rows used by the local event handler.
- [`scheduled_tasks`](tables/scheduled-tasks.md) — db-scheduler table used by distributed jobs and local handlers.

## See also

- [The outbox: atomic state + events](../concepts/outbox.md) — why `eventlog.events` exists.
- [Listen-to-yourself](../events/local-event-handler.md) — how `eventlog.event_notifications` is produced and drained.
- [Distributed jobs](../jobs/distributed-jobs.md) — how `scheduled_tasks` is used.
- [Multi-database](multi-database.md) — dialect-specific column types, schema/database naming, and index differences.
- [Sharding](sharding.md) — how these tables are provisioned per shard.
