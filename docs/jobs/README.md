# Jobs

Periodic background work that should run on **at most one** instance across a cluster — daily reports, hourly cleanups, periodic reconciliation. Built on a thin facade over [db-scheduler](https://github.com/kagkarlsson/db-scheduler).

- **[Distributed background jobs](distributed-jobs.md)** — `@EkbatanDistributedJob`, `JobRegistry`, the `scheduled_tasks` table, the dedicated jobs pool, and how the local-event-handler's fan-out + dispatch jobs plug into the same registry

← Back to [docs index](../README.md) · [Top README](../../README.md)
