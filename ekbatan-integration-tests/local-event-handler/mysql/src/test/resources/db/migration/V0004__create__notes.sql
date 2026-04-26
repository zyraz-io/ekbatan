CREATE TABLE notes (
    id CHAR(36) CHARACTER SET ascii NOT NULL,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    widget_id VARCHAR(64) NOT NULL,
    text TEXT NOT NULL,
    created_date DATETIME(6) NOT NULL,
    updated_date DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
