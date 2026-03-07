CREATE TABLE eventlog.action_events (
    id UUID PRIMARY KEY,
    started_date DATETIME(6) NOT NULL,
    completion_date DATETIME(6) NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSON NOT NULL
);

CREATE TABLE eventlog.model_events (
    id UUID PRIMARY KEY,
    action_id UUID NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    model_type VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSON NOT NULL,
    event_date DATETIME(6) NOT NULL
);

CREATE INDEX idx_model_events_action_id ON eventlog.model_events(action_id);
