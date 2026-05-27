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
-- No `WHERE delivered = FALSE` partial index — MariaDB doesn't support partial indexes.
-- The polling query filters on `delivered = FALSE` at the predicate level; the small
-- selectivity loss on the index is acceptable in practice.
CREATE INDEX events_pending_fanout ON eventlog.events (delivered, event_type, event_date);

CREATE TABLE eventlog.event_notifications (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSON         NOT NULL,
    started_date    DATETIME(6)  NOT NULL,
    completion_date DATETIME(6)  NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSON,
    event_date      DATETIME(6)  NOT NULL,
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME(6)  NOT NULL,
    created_date    DATETIME(6)  NOT NULL,
    updated_date    DATETIME(6)  NOT NULL,
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due ON eventlog.event_notifications (next_retry_at);
