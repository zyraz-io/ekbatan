# `eventlog.events`

`eventlog.events` is the durable event table used by the default `SingleTableJsonEventPersister`. Domain rows and event rows are committed in the same database transaction, so this table becomes the reliable source that local handlers, CDC connectors, routers, and broker publishers consume after commit.

**Required when:** you use the default `SingleTableJsonEventPersister`. This is the normal Ekbatan setup.

**Writer:** `ActionExecutor`, through `SingleTableJsonEventPersister`.

**Row model:** `io.ekbatan.core.action.persister.event.single_table_json.EventEntity`.

`EventEntity` is an internal row representation, not a domain `Model` or `Entity`. It has no optimistic-lock version and no soft-delete state.

## DDL

PostgreSQL can create the `eventlog` schema in the same migration that creates the table. MariaDB and MySQL create a separate `eventlog` database in `V0000`, because those engines treat schema and database as the same namespace.

### PostgreSQL

`src/main/resources/db/migration/V0001__eventlog.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.events (
    id              UUID         PRIMARY KEY,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSONB        NOT NULL,
    started_date    TIMESTAMP    NOT NULL,
    completion_date TIMESTAMP    NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSONB,
    event_date      TIMESTAMP    NOT NULL,
    delivered       BOOLEAN      NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
```

### MariaDB

The connecting user must have permission to create and access the `eventlog` database. The examples grant that permission from the container init script before Flyway runs.

`src/main/resources/db/migration/V0000__create_eventlog_database.sql`:

```sql
CREATE DATABASE IF NOT EXISTS eventlog;
```

`src/main/resources/db/migration/V0001__eventlog.sql`:

```sql
CREATE TABLE eventlog.events (
    id              UUID         PRIMARY KEY,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    delivered       BOOLEAN      NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
```

### MySQL

The connecting user must have permission to create and access the `eventlog` database. The examples grant that permission from the container init script before Flyway runs.

`src/main/resources/db/migration/V0000__create_eventlog_database.sql`:

```sql
CREATE DATABASE IF NOT EXISTS eventlog;
```

`src/main/resources/db/migration/V0001__eventlog.sql`:

```sql
CREATE TABLE eventlog.events (
    id              CHAR(36)     CHARACTER SET ascii NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       CHAR(36)     CHARACTER SET ascii NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255),
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    delivered       BOOLEAN      NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
```

## Columns

| Column | Nullable | Meaning |
|---|---:|---|
| `id` | No | Primary key of this event row. For cross-shard actions, each shard receives rows with the same event ids so every shard has a self-contained action/event picture. |
| `namespace` | No | Logical producer namespace configured on `ActionExecutor`. Use it to distinguish services or bounded contexts when several applications emit events. |
| `action_id` | No | One UUID per action execution. If one action emits multiple events, all rows from that action share the same `action_id`. |
| `action_name` | No | Simple class name of the producing action, such as `WalletDepositMoneyAction`. |
| `action_params` | No | The action params object serialized as JSON. This lets consumers understand the command that produced the event without joining to application tables. |
| `started_date` | No | UTC instant when the action's `perform()` phase began. |
| `completion_date` | No | UTC instant when the action's persist phase completed. |
| `model_id` | Yes | Identifier of the affected model. `NULL` only on sentinel rows for actions that emitted no model event. |
| `model_type` | Yes | Simple class name of the affected model, such as `Wallet`. `NULL` only on sentinel rows. |
| `event_type` | Yes | Simple class name of the event class, such as `WalletMoneyDepositedEvent`. `NULL` on sentinel rows. The default persister rejects ambiguous event simple-name collisions inside one process. |
| `payload` | Yes | Event payload serialized as JSON. `NULL` on sentinel rows. |
| `event_date` | No | UTC instant when the event was logically produced, normally aligned with the action completion time. |
| `delivered` | No | Starts as `FALSE` on every insert. The local-event-handler fan-out job flips it to `TRUE` after it materializes handler notifications. CDC-only deployments can ignore it. |

## Sentinel rows

If an action emits zero events, Ekbatan still writes one sentinel row with `model_id`, `model_type`, `event_type`, and `payload` set to `NULL`. This preserves the fact that the action happened. Event consumers should skip sentinel rows by checking `event_type IS NULL`; Ekbatan's Debezium SMTs already do that.

## Indexes

| Index | Purpose |
|---|---|
| `idx_events_action_id` on `(action_id)` | Lets tests, diagnostics, and consumers find all rows created by one action execution. |

The local event handler adds one extra fan-out index on this table. That index is documented with [`eventlog.event_notifications`](event-notifications.md) because it is only needed when local event handling is installed.

## Dialect types

| Logical column group | PostgreSQL | MariaDB | MySQL |
|---|---|---|---|
| `id`, `action_id` | `UUID` | `UUID` | `CHAR(36) CHARACTER SET ascii` |
| `action_params`, `payload` | `JSONB` | `JSON` | `JSON` |
| `started_date`, `completion_date`, `event_date` | `TIMESTAMP` | `DATETIME(6)` | `DATETIME(6)` |
| Text columns | `VARCHAR(255)` | `VARCHAR(255)` | `VARCHAR(255)` |
| `delivered` | `BOOLEAN` | `BOOLEAN` | `BOOLEAN` |

## See also

- [Framework tables](../tables.md) — table map and optionality matrix.
- [The outbox: atomic state + events](../../concepts/outbox.md) — why this table exists.
- [Streaming via Debezium](../../events/event-streaming.md) — reading committed event rows with CDC.
- [Local event handler](../../events/local-event-handler.md) — fan-out from event rows into local handler notifications.
