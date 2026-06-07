# Spring Boot sharded wallet REST example

This example runs the same wallet REST API on two Postgres shards:

- global shard: `ShardIdentifier(group=0, member=0)`
- Mexico shard: `ShardIdentifier(group=1, member=0)`

`Wallet` uses `ShardedId<Wallet>`, so the shard is encoded in the wallet id. `WalletCreateAction` chooses the shard from `countryCode`: `MX` goes to the Mexico shard, and every other value goes to the global shard. Later actions such as deposit and close use the shard encoded in the wallet id.

The example intentionally does not enable cross-shard actions. The integration test creates one wallet on each shard, deposits into each wallet through separate action executions, and verifies through `WalletRepository.existsOnShard(...)` that each database contains only its own wallet row.
