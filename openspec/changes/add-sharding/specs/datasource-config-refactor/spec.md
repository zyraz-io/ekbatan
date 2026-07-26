## ADDED Requirements

### Requirement: DataSourceConfig is a builder-based class
DataSourceConfig MUST be refactored from a Java record to a builder-based immutable class following AGENTS.md builder pattern conventions.

#### Scenario: Builder construction with required fields only
- **WHEN** `dataSourceConfig().jdbcUrl("jdbc:postgresql://host/db").username("user").password("pass").maximumPoolSize(10).build()` is called
- **THEN** a DataSourceConfig is created with optional fields as defaults

#### Scenario: Builder construction with all fields
- **WHEN** all optional fields (driverClassName, minimumIdle, idleTimeout, leakDetectionThreshold) are set
- **THEN** a DataSourceConfig is created with all values

#### Scenario: Missing required field
- **WHEN** `build()` is called without setting `jdbcUrl`
- **THEN** construction fails with validation error "jdbcUrl is required"

### Requirement: Dialect is auto-resolved from JDBC URL
The `dialect` field MUST be automatically derived from the JDBC URL during construction. There SHALL be no explicit dialect parameter.

#### Scenario: PostgreSQL URL
- **WHEN** jdbcUrl contains "postgresql" (e.g., `jdbc:postgresql://host/db` or `jdbc:aws-wrapper:postgresql://host/db`)
- **THEN** `dialect` is `SQLDialect.POSTGRES`

#### Scenario: MySQL URL
- **WHEN** jdbcUrl contains "mysql"
- **THEN** `dialect` is `SQLDialect.MYSQL`

#### Scenario: MariaDB URL
- **WHEN** jdbcUrl contains "mariadb"
- **THEN** `dialect` is `SQLDialect.MARIADB`

#### Scenario: Unknown database URL
- **WHEN** jdbcUrl does not contain any recognized database identifier
- **THEN** construction MUST fail with `IllegalArgumentException`

### Requirement: Dialect derivation uses contains not startsWith
The URL MUST be checked with `contains()` to support wrapper drivers like AWS JDBC (`jdbc:aws-wrapper:postgresql://...`).

#### Scenario: AWS wrapper driver URL
- **WHEN** jdbcUrl is `jdbc:aws-wrapper:postgresql://aurora-cluster.rds.amazonaws.com/db`
- **THEN** `dialect` is `SQLDialect.POSTGRES`
