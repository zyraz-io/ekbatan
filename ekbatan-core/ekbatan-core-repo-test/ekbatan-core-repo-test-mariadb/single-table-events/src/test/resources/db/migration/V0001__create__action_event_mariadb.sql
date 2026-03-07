CREATE TABLE eventlog.action_events (
    id UUID PRIMARY KEY,
    started_date DATETIME(6) NOT NULL,
    completion_date DATETIME(6) NOT NULL,
    action_name VARCHAR(255) NOT NULL,
    action_params JSON NOT NULL,
    model_events JSON NOT NULL
);
