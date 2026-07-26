# Typed batch update binding

## Context

`AbstractRepository` has three write paths that build multi-row SQL: the PostgreSQL batch update
(`buildUpdateAllQuery`), the MariaDB/MySQL batch update (`buildUpdateAllQueryMariadb`), and the
batch insert (`addAll`/`addAllNoResult`). Two of the three bind values through the target
`Field`'s `DataType`. The PostgreSQL batch update does not - it passes raw user-type values to
`DSL.row(Object...)`, which re-infers a `DataType` from each value's runtime class and discards
both the field's declared type and its codegen `Converter`.

See `design.md` finding 1 for the full mechanism and the rendered-SQL evidence.

## ADDED Requirements

### Requirement: Batch updates bind through the target field's DataType

Every multi-row write built by `AbstractRepository` SHALL bind each value against the target
`Field`, so that the field's declared `DataType` and any attached `Converter` or `Binding` govern
the rendered SQL and the JDBC bind type. No write path SHALL rely on jOOQ inferring a type from a
value's runtime class.

#### Scenario: Instant round-trips exactly through a batch update

- **GIVEN** a table whose timestamp column is declared as
  `SQLDataType.LOCALDATETIME.asConvertedDataType(new InstantConverter())`, per
  `docs/database/multi-database.md`
- **AND** a JVM default time zone that is not UTC
- **WHEN** an action stages updates to two or more aggregates of the same type and they are
  persisted on PostgreSQL
- **THEN** each `Instant` SHALL be stored unshifted, and re-reading the row SHALL return the exact
  `Instant` that was written

#### Scenario: All-null nullable column does not fail the statement

- **GIVEN** a batch update over two or more rows in which a nullable non-text column is null in
  every row
- **WHEN** the update is executed on PostgreSQL
- **THEN** the statement SHALL succeed, and SHALL NOT fail with SQLSTATE 42804
  (`datatype_mismatch`)

#### Scenario: Converters survive the batch path

- **GIVEN** a column with a codegen `Converter` (for example `JSONB` <-> `ObjectNode` on
  PostgreSQL)
- **WHEN** it is written via a multi-row `updateAll` / `updateAllNoResult`
- **THEN** the value SHALL be converted exactly as it is on the single-row `update()` path

### Requirement: Batch and single-row write paths agree

The SQL produced for a given aggregate SHALL be type-equivalent whether it is persisted alone or
as part of a batch, and whether the dialect is PostgreSQL, MySQL, or MariaDB.

#### Scenario: Single-row and batch produce the same stored value

- **WHEN** the same aggregate is persisted once through the `size() == 1` short-circuit and once
  as part of a two-element batch
- **THEN** the stored column values SHALL be identical
