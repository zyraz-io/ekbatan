## ADDED Requirements

### Requirement: ConnectionProvider no longer appends targetServerType
The `boolean primary` flag MUST be removed from `hikariConnectionProvider()`. The JDBC URL from DataSourceConfig SHALL be used as-is without any framework-appended parameters.

#### Scenario: URL passed through unmodified
- **WHEN** `hikariConnectionProvider(config)` is called with a DataSourceConfig
- **THEN** the HikariCP pool is configured with `config.jdbcUrl` exactly as provided

#### Scenario: PostgreSQL with targetServerType
- **WHEN** the user provides `jdbc:postgresql://host/db?targetServerType=master`
- **THEN** that exact URL is used for the connection pool

#### Scenario: MySQL without targetServerType
- **WHEN** the user provides `jdbc:mysql://host/db`
- **THEN** that exact URL is used — no PostgreSQL-specific params added

### Requirement: Method signature simplified
The `boolean primary` parameter MUST be removed from the `hikariConnectionProvider` method signature.

#### Scenario: New signature
- **WHEN** `ConnectionProvider.hikariConnectionProvider(config)` is called
- **THEN** a ConnectionProvider wrapping a HikariDataSource is returned
