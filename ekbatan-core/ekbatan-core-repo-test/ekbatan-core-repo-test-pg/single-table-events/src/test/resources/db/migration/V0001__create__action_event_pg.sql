CREATE SCHEMA IF NOT EXISTS eventlog;

CREATE TABLE eventlog.action_events (
    id UUID PRIMARY KEY,
    started_date TIMESTAMP NOT NULL,
    completion_date TIMESTAMP NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSONB NOT NULL,
    model_events JSONB NOT NULL
);
