CREATE TABLE eventlog.event_notifications (
    id              UUID         PRIMARY KEY,
    event_id        UUID         NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    -- denormalized event + action context — copied from eventlog.events at fan-out time so
    -- dispatch reads everything it needs from a single row, no JOIN or hydration query.
    namespace       VARCHAR(255) NOT NULL,
    action_id       UUID         NOT NULL,
    action_name     VARCHAR(255) NOT NULL,
    action_params   JSONB        NOT NULL,
    started_date    TIMESTAMP    NOT NULL,
    completion_date TIMESTAMP    NOT NULL,
    model_id        VARCHAR(255),
    model_type      VARCHAR(255),
    event_type      VARCHAR(255) NOT NULL,
    payload         JSONB,
    event_date      TIMESTAMP    NOT NULL,
    -- delivery state
    state           VARCHAR(24)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP    NOT NULL,
    created_date    TIMESTAMP    NOT NULL,
    updated_date    TIMESTAMP    NOT NULL,
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due
    ON eventlog.event_notifications (next_retry_at)
    WHERE state IN ('PENDING', 'FAILED');

-- Also flag undelivered events on eventlog.events. The fanout job uses this column.
ALTER TABLE eventlog.events
    ADD COLUMN delivered BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX events_undelivered ON eventlog.events (event_date) WHERE delivered = FALSE;
