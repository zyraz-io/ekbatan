-- db-scheduler's required schema for MariaDB / MySQL. Verbatim from
-- https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/resources/mysql_tables.sql
-- This is db-scheduler's own table — Ekbatan intentionally steps off the always-DATETIME(6) rule
-- here because db-scheduler owns the schema.
create table scheduled_tasks (
    task_name VARCHAR(100) NOT NULL,
    task_instance VARCHAR(100) NOT NULL,
    task_data BLOB,
    execution_time TIMESTAMP(6) NOT NULL,
    picked BOOLEAN NOT NULL,
    picked_by VARCHAR(50),
    last_success TIMESTAMP(6) NULL,
    last_failure TIMESTAMP(6) NULL,
    consecutive_failures INT,
    last_heartbeat TIMESTAMP(6) NULL,
    version BIGINT NOT NULL,
    priority SMALLINT,
    PRIMARY KEY (task_name, task_instance),
    INDEX execution_time_idx (execution_time),
    INDEX last_heartbeat_idx (last_heartbeat),
    INDEX priority_execution_time_idx (priority, execution_time)
);
