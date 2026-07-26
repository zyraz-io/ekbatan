## ADDED Requirements

### Requirement: Cross-shard partial failure warning logging
When `ActionExecutor.persistChanges()` executes a multi-shard action (`allowCrossShard = true`) and one or more shards commit successfully before a subsequent shard throws an exception, `ActionExecutor` SHALL output a high-priority structured warning log (`LOG.error` / `LOG.warn`) explicitly identifying the action name, the committed shards, the failing shard, and the exception.

#### Scenario: Partial cross-shard commit failure
- **WHEN** an action spans Shard A and Shard B with `allowCrossShard = true`, and Shard A commits successfully but Shard B persistence fails with an exception
- **THEN** the framework SHALL log an explicit ERROR warning identifying Shard A as committed and Shard B as failed, before re-throwing the exception to the caller
