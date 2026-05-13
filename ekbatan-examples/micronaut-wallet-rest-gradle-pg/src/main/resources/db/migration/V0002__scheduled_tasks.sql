-- db-scheduler's required schema for PostgreSQL.
-- This is db-scheduler's own table (verbatim from its repo); the framework intentionally steps
-- off the always-TIMESTAMP rule here — db-scheduler owns the table and its schema.
create table scheduled_tasks (
    task_name text not null,
    task_instance text not null,
    task_data bytea,
    execution_time timestamp with time zone not null,
    picked BOOLEAN not null,
    picked_by text,
    last_success timestamp with time zone,
    last_failure timestamp with time zone,
    consecutive_failures INT,
    last_heartbeat timestamp with time zone,
    version BIGINT not null,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority desc, execution_time asc);
