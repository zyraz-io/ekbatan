CREATE TABLE eventlog.events (
    id UUID PRIMARY KEY,
    namespace VARCHAR(255) NOT NULL,
    action_id UUID NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSON NOT NULL,
    started_date DATETIME(6) NOT NULL,
    completion_date DATETIME(6) NOT NULL,
    model_id VARCHAR(255),
    model_type VARCHAR(255),
    event_type VARCHAR(255),
    payload JSON,
    event_date DATETIME(6) NOT NULL,
    delivered BOOLEAN NOT NULL
);

CREATE INDEX idx_events_action_id ON eventlog.events(action_id);

CREATE INDEX events_pending_fanout ON eventlog.events (event_date);
