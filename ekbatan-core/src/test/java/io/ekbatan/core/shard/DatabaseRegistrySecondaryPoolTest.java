package io.ekbatan.core.shard;

import static io.ekbatan.core.config.DataSourceConfig.Builder.dataSourceConfig;
import static io.ekbatan.core.config.ShardGroupConfig.Builder.shardGroupConfig;
import static io.ekbatan.core.config.ShardMemberConfig.Builder.shardMemberConfig;
import static io.ekbatan.core.config.ShardingConfig.Builder.shardingConfig;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.config.DataSourceConfig;
import io.ekbatan.core.config.ShardingConfig;
import org.junit.jupiter.api.Test;

/**
 * How many connection pools a shard opens.
 *
 * <p>{@code fromConfig} used to fall back on the primary <em>config</em> when a member declared no
 * read replica, then hand that config to the pool factory a second time. Identical settings do not
 * produce the same pool - they produce a second one against the same database, with its own
 * connections and its own housekeeping threads. Every no-replica member therefore held twice the
 * configured maximum: eight shards at 20 is 320 connections where the operator budgeted 160, on a
 * PostgreSQL whose {@code max_connections} is commonly 200.
 *
 * <p>Nothing failed and nothing was logged - both pools were healthy - which is why it needs a test
 * rather than an integration check. The pools here point at hosts that do not resolve;
 * {@code initializationFailTimeout = -1} means construction never contacts them.
 */
class DatabaseRegistrySecondaryPoolTest {

    private static final DataSourceConfig PRIMARY = dataSourceConfig()
            .jdbcUrl("jdbc:postgresql://primary-9e3f:5432/db")
            .username("user")
            .password("pass")
            .maximumPoolSize(20)
            .build();

    private static final DataSourceConfig REPLICA = dataSourceConfig()
            .jdbcUrl("jdbc:postgresql://replica-9e3f:5432/db")
            .username("user")
            .password("pass")
            .maximumPoolSize(20)
            .build();

    /** The regression: no replica configured means one pool, shared - not two identical ones. */
    @Test
    void a_member_with_no_replica_shares_one_pool() {
        try (var registry = DatabaseRegistry.fromConfig(config(null))) {
            var tm = registry.defaultTransactionManager();

            assertThat(tm.secondaryConnectionProvider)
                    .as("a member with no replica must reuse the primary pool, not open a second one"
                            + " against the same database")
                    .isSameAs(tm.primaryConnectionProvider);
        }
    }

    /** A real replica is still its own pool - the fix must not collapse a genuine second database. */
    @Test
    void a_member_with_a_replica_keeps_two_pools() {
        try (var registry = DatabaseRegistry.fromConfig(config(REPLICA))) {
            var tm = registry.defaultTransactionManager();

            assertThat(tm.secondaryConnectionProvider).isNotSameAs(tm.primaryConnectionProvider);
            assertThat(tm.secondaryConnectionProvider.jdbcUrl()).isEqualTo(REPLICA.jdbcUrl);
        }
    }

    /**
     * Sharing is what {@code TransactionManager#close} already assumed: it closes the secondary
     * only when it is a different object. Closing a shared provider twice would otherwise be the
     * bug's mirror image.
     */
    @Test
    void closing_a_shared_provider_happens_once_and_stays_closed() {
        var registry = DatabaseRegistry.fromConfig(config(null));
        var tm = registry.defaultTransactionManager();

        registry.close();

        assertThat(tm.primaryConnectionProvider).isSameAs(tm.secondaryConnectionProvider);
        // Idempotent by Hikari's contract; the point is that close() completed without attempting
        // a second close on a provider it does not separately own.
        registry.close();
    }

    /** Every member gets its own primary - sharing is within a member, never across them. */
    @Test
    void separate_members_never_share_a_pool() {
        var config = shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("g0")
                        .withMember(shardMemberConfig()
                                .member(0)
                                .primaryConfig(PRIMARY)
                                .build())
                        .withMember(shardMemberConfig()
                                .member(1)
                                .primaryConfig(REPLICA)
                                .build())
                        .build())
                .build();

        try (var registry = DatabaseRegistry.fromConfig(config)) {
            var first = registry.transactionManager(ShardIdentifier.of(0, 0));
            var second = registry.transactionManager(ShardIdentifier.of(0, 1));

            assertThat(first.primaryConnectionProvider).isNotSameAs(second.primaryConnectionProvider);
        }
    }

    private static ShardingConfig config(DataSourceConfig replica) {
        var member = shardMemberConfig().member(0).primaryConfig(PRIMARY);
        if (replica != null) {
            member.secondaryConfig(replica);
        }
        return shardingConfig()
                .defaultShard(ShardIdentifier.of(0, 0))
                .withGroup(shardGroupConfig()
                        .group(0)
                        .name("g0")
                        .withMember(member.build())
                        .build())
                .build();
    }
}
