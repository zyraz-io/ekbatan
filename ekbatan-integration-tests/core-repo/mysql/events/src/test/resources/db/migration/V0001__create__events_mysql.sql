CREATE TABLE eventlog.events (
    id CHAR(36) CHARACTER SET ascii NOT NULL,
    namespace VARCHAR(255) NOT NULL,
    action_id CHAR(36) CHARACTER SET ascii NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSON NOT NULL,
    started_date DATETIME(6) NOT NULL,
    completion_date DATETIME(6) NOT NULL,
    model_id VARCHAR(255),
    model_type VARCHAR(255),
    event_type VARCHAR(255),
    payload JSON,
    event_date DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);
