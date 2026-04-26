CREATE TABLE eventlog.event_notifications (
    id              CHAR(36)     CHARACTER SET ascii NOT NULL,
    event_id        CHAR(36)     CHARACTER SET ascii NOT NULL,
    handler_name    VARCHAR(255) NOT NULL,
    namespace       VARCHAR(255) NOT NULL,
    action_id       CHAR(36)     CHARACTER SET ascii NOT NULL,
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
    PRIMARY KEY (id),
    UNIQUE (event_id, handler_name)
);

CREATE INDEX event_notifications_due ON eventlog.event_notifications (next_retry_at);
