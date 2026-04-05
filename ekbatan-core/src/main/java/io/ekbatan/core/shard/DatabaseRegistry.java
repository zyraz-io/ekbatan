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
        this.defaultShard = Validate.notNull(builder.defaultShard, "defaultShard is required");
        Validate.isTrue(!this.transactionManagers.isEmpty(), "at least one database is required");
        Validate.isTrue(
                this.transactionManagers.containsKey(this.defaultShard),
                "defaultShard must reference a registered database");

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
        var builder = databaseRegistry().defaultShard(config.defaultShard);

        for (var group : config.groups) {
            for (var member : group.members) {
                var id = ShardIdentifier.of(group.group, member.member);
                var primaryProvider = ConnectionProvider.hikariConnectionProvider(member.primaryConfig);
                var secondaryProvider = ConnectionProvider.hikariConnectionProvider(member.secondaryConfig);
                var tm = new TransactionManager(primaryProvider, secondaryProvider, member.primaryConfig.dialect, id);
                builder.withDatabase(id, tm);
            }
        }

        return builder.build();
    }

    public static final class Builder {

        private final Map<ShardIdentifier, TransactionManager> transactionManagers = new LinkedHashMap<>();
        private ShardIdentifier defaultShard;

        private Builder() {}

        public static Builder databaseRegistry() {
            return new Builder();
        }

        public Builder withDatabase(ShardIdentifier id, TransactionManager tm) {
            this.transactionManagers.put(id, tm);
            return this;
        }

        public Builder defaultShard(ShardIdentifier defaultShard) {
            this.defaultShard = defaultShard;
            return this;
        }

        public DatabaseRegistry build() {
            return new DatabaseRegistry(this);
        }
    }
}
