CREATE TABLE widgets (
    id CHAR(36) CHARACTER SET ascii NOT NULL,
    version BIGINT NOT NULL,
    state VARCHAR(24) NOT NULL,
    name VARCHAR(255) NOT NULL,
    color VARCHAR(24) NOT NULL,
    created_date DATETIME(6) NOT NULL,
    updated_date DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);
