CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.action_events (
    id UUID PRIMARY KEY,
    started_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSONB NOT NULL
);

CREATE TABLE eventlog.model_events (
    id UUID PRIMARY KEY,
    action_id UUID NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    model_type VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    event_data TIMESTAMP NOT NULL
);