# Event dispatch fault isolation

## Context

`EventHandlingJob` forks one virtual thread per due notification, classifies each outcome, and
then applies four bucket UPDATEs (`markExpiredAllPreflight`, `markSucceededAll`,
`markExpiredAllPostFailure`, `markFailedBucket`). `classify()` catches `InterruptedException` and
`Exception`, but not `Error`, so an `Error` from user handler code escapes the fork and is
rethrown upstream of every state write. The whole batch is discarded with zero rows written, and
because `next_retry_at` is never advanced the poison row stays at the head of `findDue`'s
ordering and recurs on every poll.

`EventFanoutJob` has a related but distinct defect: it uses the replica read count as its progress
signal, so replication lag turns the round loop into a busy loop. See `design.md` findings 3 and 6.

## ADDED Requirements

### Requirement: A single handler fault cannot stall a shard's delivery

`EventHandlingJob` SHALL treat any `Throwable` raised by a handler as a failed outcome for that
notification alone. No handler fault SHALL prevent the batch's state writes from being applied.

#### Scenario: Handler throws an Error

- **GIVEN** a batch of due notifications in which one handler throws `NoClassDefFoundError`,
  `AssertionError`, or `StackOverflowError`
- **WHEN** the batch is processed
- **THEN** that notification SHALL be recorded as `FAILED` with its cause logged, its `attempts`
  incremented and its `next_retry_at` advanced by the configured backoff
- **AND** every other notification in the batch SHALL be recorded with its own outcome
- **AND** the following poll SHALL make progress rather than re-processing the identical batch

#### Scenario: The batch commits even if a fork dies abnormally

- **WHEN** a fork terminates in a way that is not representable as an outcome
- **THEN** the outcomes already decided for the rest of the batch SHALL still be written

### Requirement: Fan-out progress is measured by rows written

`EventFanoutJob` SHALL derive its round-progress signal and its `EVENTS_FANNED_OUT` /
`NOTIFICATIONS_CREATED` metrics from rows actually written on the primary, not from rows read
from the read replica.

#### Scenario: Replication lag does not cause a busy loop

- **GIVEN** a deployment with a distinct read-replica pool and non-zero replication lag
- **WHEN** a fan-out round has already flipped `delivered` for the events it read, and the next
  round re-reads the same rows from the lagging replica
- **THEN** that round SHALL report no progress, and `execute()` SHALL sleep for `pollDelay`
- **AND** `markDelivered` SHALL constrain its update with `delivered = false`, so its update count
  is exactly the number of events genuinely transitioned
