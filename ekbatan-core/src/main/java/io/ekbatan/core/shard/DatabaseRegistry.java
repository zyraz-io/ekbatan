package io.ekbatan.core.shard;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;

import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.shard.config.ShardingConfig;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.Validate;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class DatabaseRegistry {

    private final Map<ShardIdentifier, TransactionManager> transactionManagers;
    public final Map<ShardIdentifier, DSLContext> primary;
    public final Map<ShardIdentifier, DSLContext> secondary;
    public final ShardIdentifier defaultShard;

    private DatabaseRegistry(Builder builder) {
        this.transactionManagers = Collections.unmodifiableMap(builder.transactionManagers);
        Validate.isTrue(!this.transactionManagers.isEmpty(), "at least one database is required");
        this.defaultShard = builder.defaultShard.orElseGet(() -> {
            Validate.isTrue(
                    this.transactionManagers.size() == 1,
                    "withDefaultDatabase must be called when more than one database is registered");
            return this.transactionManagers.keySet().iterator().next();
        });

        var p = new HashMap<ShardIdentifier, DSLContext>();
        var s = new HashMap<ShardIdentifier, DSLContext>();
        this.transactionManagers.forEach((id, tm) -> {
            p.put(id, DSL.using(tm.primaryConnectionProvider.getDataSource(), tm.dialect));
            s.put(id, DSL.using(tm.secondaryConnectionProvider.getDataSource(), tm.dialect));
        });
        this.primary = Map.copyOf(p);
        this.secondary = Map.copyOf(s);
    }

    public TransactionManager transactionManager(ShardIdentifier id) {
        var tm = transactionManagers.get(id);
        if (tm == null) {
            throw new IllegalArgumentException("No database registered for shard: " + id);
        }
        return tm;
    }

    public ShardIdentifier effectiveShard(ShardIdentifier shard) {
        return transactionManagers.containsKey(shard) ? shard : defaultShard;
    }

    public TransactionManager defaultTransactionManager() {
        return transactionManager(defaultShard);
    }

    public Collection<TransactionManager> allTransactionManagers() {
        return transactionManagers.values();
    }

    public static DatabaseRegistry fromConfig(ShardingConfig config) {
        var builder = databaseRegistry();
        var defaultRegistered = false;

        for (var group : config.groups) {
            for (var member : group.members) {
                var shardIdentifier = ShardIdentifier.of(group.group, member.member);
                var primaryProvider = ConnectionProvider.hikariConnectionProvider(member.primaryConfig);
                var secondaryProvider = ConnectionProvider.hikariConnectionProvider(member.secondaryConfig);
                var tm = new TransactionManager(
                        primaryProvider, secondaryProvider, member.primaryConfig.dialect, shardIdentifier);
                if (shardIdentifier.equals(config.defaultShard)) {
                    builder.withDefaultDatabase(tm);
                    defaultRegistered = true;
                } else {
                    builder.withDatabase(tm);
                }
            }
        }

        Validate.isTrue(defaultRegistered, "defaultShard must reference a registered database");
        return builder.build();
    }

    public static final class Builder {

        private final Map<ShardIdentifier, TransactionManager> transactionManagers = new LinkedHashMap<>();
        private Optional<ShardIdentifier> defaultShard = Optional.empty();

        private Builder() {}

        public static Builder databaseRegistry() {
            return new Builder();
        }

        public Builder withDatabase(TransactionManager tm) {
            this.transactionManagers.put(tm.shardIdentifier, tm);
            return this;
        }

        public Builder withDefaultDatabase(TransactionManager tm) {
            this.transactionManagers.put(tm.shardIdentifier, tm);
            this.defaultShard = Optional.of(tm.shardIdentifier);
            return this;
        }

        public DatabaseRegistry build() {
            return new DatabaseRegistry(this);
        }
    }
}
