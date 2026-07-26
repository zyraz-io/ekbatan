# Transaction cleanup safety

## Context

`Transaction` captures the pooled connection's `initialAutoCommit` (true under Hikari defaults),
sets it false in `begin()`, and restores it during `commit()` / `rollback()`. In `rollback()` the
restore sits in a `finally` that also runs when `connection.rollback()` threw. Because
`Connection.setAutoCommit` is specified to commit an in-progress transaction when the mode
changes - and MySQL/MariaDB document `SET autocommit = 1` as an implicit `COMMIT` - the framework
itself commits the transaction it just failed to roll back.

The class's own comment names this exact hazard as the thing its dirty-flag guard defends
against. See `design.md` finding 2.

## ADDED Requirements

### Requirement: Connection state is restored only after successful completion

`Transaction` SHALL restore the connection's original autocommit mode only after `commit()` or
`rollback()` has returned normally. When the completion call itself fails, the connection SHALL be
left with autocommit disabled, marked dirty, and evicted rather than returned to the pool.

#### Scenario: Failed rollback does not become a commit

- **GIVEN** an open transaction on a connection whose `initialAutoCommit` is `true`
- **WHEN** `connection.rollback()` throws `SQLException` while the session is still alive
- **THEN** `setAutoCommit` SHALL NOT be called on that connection
- **AND** the transaction SHALL be marked dirty so the caller evicts the connection, whose
  physical close aborts the pending transaction server-side

#### Scenario: Failed commit follows the same rule

- **WHEN** `connection.commit()` throws
- **THEN** the autocommit restore SHALL be skipped, leaving auto-commit disabled so the rollback
  that follows can actually roll the transaction back
- **AND** the transaction SHALL NOT be marked dirty on that basis alone: if the follow-up rollback
  succeeds the connection is clean and SHALL be returned to the pool. It is marked dirty only when
  that rollback, or its own restore, also fails.

#### Scenario: Happy path is unchanged

- **WHEN** `commit()` or `rollback()` returns normally
- **THEN** autocommit SHALL be restored to `initialAutoCommit` and the connection SHALL be
  returned to the pool as it is today
