package io.ekbatan.core.test.repository;

import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static io.ekbatan.core.test.model.DummyBuilder.dummy;
import static io.ekbatan.core.test.model.DummyState.OPENED;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.repository.AbstractRepository;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardedUUID;
import io.ekbatan.core.test.model.Dummy;
import io.ekbatan.core.test.model.events.DummyCreatedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

public abstract class BaseShardedRepositoryTest {

    protected static final ShardIdentifier SHARD_A = ShardIdentifier.of(0, 0);
    protected static final ShardIdentifier SHARD_B = ShardIdentifier.of(1, 0);
    protected static final ShardIdentifier UNREGISTERED_SHARD = ShardIdentifier.of(2, 0);

    protected final DatabaseRegistry databaseRegistry;
    protected final AbstractRepository<Dummy, ?, ?, UUID> repository;

    public BaseShardedRepositoryTest(
            DatabaseRegistry databaseRegistry, AbstractRepository<Dummy, ?, ?, UUID> repository) {
        this.databaseRegistry = databaseRegistry;
        this.repository = repository;
    }

    @Test
    void should_add_to_correct_shard_and_find_on_that_shard() {
        // GIVEN — a dummy with ShardedUUID targeting SHARD_A
        var dummyA = createShardedDummy(SHARD_A);

        // WHEN
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.add(dummyA);
        });

        // THEN — findable via the sharded repo
        assertThat(repository.findById(dummyA.id.getValue())).isPresent();
    }

    @Test
    void should_isolate_data_between_shards() {
        // GIVEN — dummies on different shards
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);

        // WHEN — add each to its shard
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.add(dummyA);
        });
        databaseRegistry.transactionManager(SHARD_B).inTransaction(_ -> {
            repository.add(dummyB);
        });

        // THEN — sharded repo can find both (routes by UUID bits)
        assertThat(repository.findById(dummyA.id.getValue())).isPresent();
        assertThat(repository.findById(dummyB.id.getValue())).isPresent();

        // AND — create per-shard repos to verify isolation
        var shardATm = databaseRegistry.transactionManager(SHARD_A);
        var shardAOnlyRegistry = databaseRegistry().withDatabase(shardATm).build();
        var shardAOnlyRepo = createRepository(shardAOnlyRegistry);

        var shardBTm = databaseRegistry.transactionManager(SHARD_B);
        var shardBOnlyRegistry = databaseRegistry().withDatabase(shardBTm).build();
        var shardBOnlyRepo = createRepository(shardBOnlyRegistry);

        // THEN — shard A repo sees dummyA but NOT dummyB
        assertThat(shardAOnlyRepo.findById(dummyA.id.getValue())).isPresent();
        assertThat(shardAOnlyRepo.findById(dummyB.id.getValue())).isEmpty();

        // AND — shard B repo sees dummyB but NOT dummyA
        assertThat(shardBOnlyRepo.findById(dummyB.id.getValue())).isPresent();
        assertThat(shardBOnlyRepo.findById(dummyA.id.getValue())).isEmpty();
    }

    @Test
    void should_update_on_correct_shard() {
        // GIVEN — a dummy on SHARD_A
        var dummy = createShardedDummy(SHARD_A);
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.add(dummy);
        });

        // WHEN — update (deposit)
        var updated = dummy.deposit(BigDecimal.valueOf(50));
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.update(updated);
        });

        // THEN — updated on shard A
        var fetched = repository.findById(dummy.id.getValue());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().balance).isEqualByComparingTo(BigDecimal.valueOf(60)); // 10 + 50
    }

    @Test
    void should_rollback_on_correct_shard() {
        // GIVEN — a dummy on SHARD_B
        var dummy = createShardedDummy(SHARD_B);

        // WHEN — add in transaction that throws
        try {
            databaseRegistry
                    .transactionManager(SHARD_B)
                    .inTransaction((java.util.function.Consumer<org.jooq.DSLContext>) _ -> {
                        repository.add(dummy);
                        throw new RuntimeException("rollback");
                    });
        } catch (Exception _) {
        }

        // THEN — not persisted on shard B
        assertThat(repository.findById(dummy.id.getValue())).isEmpty();
    }

    @Test
    void should_add_without_transaction_routes_to_correct_shard() {
        // GIVEN — dummies on different shards
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);

        // WHEN — add WITHOUT transaction (goes through db(PERSISTABLE) directly)
        repository.add(dummyA);
        repository.add(dummyB);

        // THEN — each routed to correct shard
        assertThat(repository.findById(dummyA.id.getValue())).isPresent();
        assertThat(repository.findById(dummyB.id.getValue())).isPresent();

        // AND — verify isolation
        var shardAOnlyRepo = createRepository(singleShardRegistry(SHARD_A));
        var shardBOnlyRepo = createRepository(singleShardRegistry(SHARD_B));

        assertThat(shardAOnlyRepo.findById(dummyA.id.getValue())).isPresent();
        assertThat(shardAOnlyRepo.findById(dummyB.id.getValue())).isEmpty();
        assertThat(shardBOnlyRepo.findById(dummyB.id.getValue())).isPresent();
        assertThat(shardBOnlyRepo.findById(dummyA.id.getValue())).isEmpty();
    }

    @Test
    void should_findById_inside_transaction_on_correct_shard() {
        // GIVEN — a dummy on SHARD_B
        var dummy = createShardedDummy(SHARD_B);
        databaseRegistry.transactionManager(SHARD_B).inTransaction(_ -> {
            repository.add(dummy);
        });

        // WHEN / THEN — findById inside a transaction on SHARD_B sees the dummy
        databaseRegistry.transactionManager(SHARD_B).inTransaction(_ -> {
            var found = repository.findById(dummy.id.getValue());
            assertThat(found).isPresent();
            assertThat(found.get().id).isEqualTo(dummy.id);
        });
    }

    @Test
    void should_addAll_in_transaction_on_correct_shard() {
        // GIVEN — two dummies on SHARD_A
        var dummy1 = createShardedDummy(SHARD_A);
        var dummy2 = createShardedDummy(SHARD_A);

        // WHEN — addAll in transaction
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.addAllNoResult(java.util.List.of(dummy1, dummy2));
        });

        // THEN — both on shard A
        assertThat(repository.findById(dummy1.id.getValue())).isPresent();
        assertThat(repository.findById(dummy2.id.getValue())).isPresent();

        // AND — not on shard B
        var shardBOnlyRepo = createRepository(singleShardRegistry(SHARD_B));
        assertThat(shardBOnlyRepo.findById(dummy1.id.getValue())).isEmpty();
        assertThat(shardBOnlyRepo.findById(dummy2.id.getValue())).isEmpty();
    }

    @Test
    void should_updateAll_in_transaction_on_correct_shard() {
        // GIVEN — two dummies on SHARD_A
        var dummy1 = createShardedDummy(SHARD_A);
        var dummy2 = createShardedDummy(SHARD_A);
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.addAllNoResult(java.util.List.of(dummy1, dummy2));
        });

        // WHEN — update both
        var updated1 = dummy1.deposit(BigDecimal.valueOf(5));
        var updated2 = dummy2.deposit(BigDecimal.valueOf(7));
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.updateAllNoResult(java.util.List.of(updated1, updated2));
        });

        // THEN — both updated on shard A
        var fetched1 = repository.findById(dummy1.id.getValue());
        var fetched2 = repository.findById(dummy2.id.getValue());
        assertThat(fetched1).isPresent();
        assertThat(fetched2).isPresent();
        assertThat(fetched1.get().balance).isEqualByComparingTo(BigDecimal.valueOf(15)); // 10 + 5
        assertThat(fetched2.get().balance).isEqualByComparingTo(BigDecimal.valueOf(17)); // 10 + 7
    }

    @Test
    void should_rollback_addAll_on_correct_shard() {
        // GIVEN — two dummies on SHARD_B
        var dummy1 = createShardedDummy(SHARD_B);
        var dummy2 = createShardedDummy(SHARD_B);

        // WHEN — addAll in transaction that throws
        try {
            databaseRegistry
                    .transactionManager(SHARD_B)
                    .inTransaction((java.util.function.Consumer<org.jooq.DSLContext>) _ -> {
                        repository.addAllNoResult(java.util.List.of(dummy1, dummy2));
                        throw new RuntimeException("rollback");
                    });
        } catch (Exception _) {
        }

        // THEN — neither persisted
        assertThat(repository.findById(dummy1.id.getValue())).isEmpty();
        assertThat(repository.findById(dummy2.id.getValue())).isEmpty();
    }

    @Test
    void transaction_on_shard_A_does_not_affect_shard_B() {
        // GIVEN — a dummy on each shard, persisted
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.add(dummyA);
        });
        databaseRegistry.transactionManager(SHARD_B).inTransaction(_ -> {
            repository.add(dummyB);
        });

        // WHEN — rollback an update on shard A
        var updatedA = dummyA.deposit(BigDecimal.valueOf(100));
        try {
            databaseRegistry
                    .transactionManager(SHARD_A)
                    .inTransaction((java.util.function.Consumer<org.jooq.DSLContext>) _ -> {
                        repository.update(updatedA);
                        throw new RuntimeException("rollback shard A");
                    });
        } catch (Exception _) {
        }

        // THEN — shard A dummy unchanged (rollback worked)
        var fetchedA = repository.findById(dummyA.id.getValue());
        assertThat(fetchedA).isPresent();
        assertThat(fetchedA.get().balance).isEqualByComparingTo(BigDecimal.TEN); // original

        // AND — shard B dummy unaffected
        var fetchedB = repository.findById(dummyB.id.getValue());
        assertThat(fetchedB).isPresent();
        assertThat(fetchedB.get().balance).isEqualByComparingTo(BigDecimal.TEN); // untouched
    }

    // --- Scatter-gather read tests ---

    @Test
    void should_findAll_across_shards() {
        // GIVEN — one dummy per shard
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        // WHEN
        var all = repository.findAll();

        // THEN — sees both
        assertThat(all).extracting(d -> d.id).contains(dummyA.id, dummyB.id);
    }

    @Test
    void should_count_across_shards() {
        // GIVEN
        var beforeCount = repository.count();
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        // WHEN / THEN
        assertThat(repository.count()).isEqualTo(beforeCount + 2);
    }

    @Test
    void should_countWhere_across_shards() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A, "EUR");
        var dummyB = createShardedDummy(SHARD_B, "EUR");
        var dummyC = createShardedDummy(SHARD_A, "USD");
        repository.add(dummyA);
        repository.add(dummyB);
        repository.add(dummyC);

        // WHEN
        var eurCount = repository.countWhere(DSL.field("currency").eq("EUR"));

        // THEN — at least 2 EUR (one per shard)
        assertThat(eurCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void should_findAllWhere_across_shards() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A, "GBP");
        var dummyB = createShardedDummy(SHARD_B, "GBP");
        repository.add(dummyA);
        repository.add(dummyB);

        // WHEN
        var gbpDummies = repository.findAllWhere(DSL.field("currency").eq("GBP"));

        // THEN
        assertThat(gbpDummies).extracting(d -> d.id).contains(dummyA.id, dummyB.id);
    }

    @Test
    void should_findOneWhere_across_shards() {
        // GIVEN — a dummy with unique currency on SHARD_B
        var dummy = createShardedDummy(SHARD_B, "CHF");
        repository.add(dummy);

        // WHEN
        var found = repository.findOneWhere(DSL.field("currency").eq("CHF"));

        // THEN
        assertThat(found).isPresent();
        assertThat(found.get().id).isEqualTo(dummy.id);
    }

    @Test
    void should_existsWhere_across_shards() {
        // GIVEN — a dummy with unique currency on SHARD_B
        var dummy = createShardedDummy(SHARD_B, "SEK");
        repository.add(dummy);

        // WHEN / THEN
        assertThat(repository.existsWhere(DSL.field("currency").eq("SEK"))).isTrue();
        assertThat(repository.existsWhere(DSL.field("currency").eq("ZZZ"))).isFalse();
    }

    @Test
    void should_existsById_on_correct_shard() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        // WHEN / THEN
        assertThat(repository.existsById(dummyA.id.getValue())).isTrue();
        assertThat(repository.existsById(dummyB.id.getValue())).isTrue();
        assertThat(repository.existsById(randomUUID())).isFalse();
    }

    // --- findAllByIds across shards ---

    @Test
    void should_findAllByIds_across_shards() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        // WHEN
        var found = repository.findAllByIds(List.of(dummyA.id.getValue(), dummyB.id.getValue()));

        // THEN
        assertThat(found).hasSize(2);
        assertThat(found).extracting(d -> d.id).containsExactlyInAnyOrder(dummyA.id, dummyB.id);
    }

    @Test
    void should_findAllByIds_with_nonexistent_ids() {
        // GIVEN
        var dummy = createShardedDummy(SHARD_A);
        repository.add(dummy);

        // WHEN
        var found = repository.findAllByIds(List.of(dummy.id.getValue(), randomUUID()));

        // THEN
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().id).isEqualTo(dummy.id);
    }

    // --- Batch cross-shard rejection ---

    @Test
    void should_reject_addAll_across_shards() {
        // GIVEN — dummies on different shards
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.addAll(List.of(dummyA, dummyB))).isInstanceOf(CrossShardException.class);
    }

    @Test
    void should_reject_addAllNoResult_across_shards() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.addAllNoResult(List.of(dummyA, dummyB)))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void should_reject_updateAll_across_shards() {
        // GIVEN — persisted dummies on different shards
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        var updatedA = dummyA.deposit(BigDecimal.ONE);
        var updatedB = dummyB.deposit(BigDecimal.ONE);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.updateAll(List.of(updatedA, updatedB)))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void should_reject_updateAllNoResult_across_shards() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        repository.add(dummyA);
        repository.add(dummyB);

        var updatedA = dummyA.deposit(BigDecimal.ONE);
        var updatedB = dummyB.deposit(BigDecimal.ONE);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.updateAllNoResult(List.of(updatedA, updatedB)))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void should_reject_cross_shard_batch_without_persisting_anything() {
        // GIVEN
        var beforeA = countDummies(SHARD_A);
        var beforeB = countDummies(SHARD_B);
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);

        // WHEN / THEN
        assertThatThrownBy(() -> repository.addAll(List.of(dummyA, dummyB))).isInstanceOf(CrossShardException.class);

        // AND — no new dummies on either shard
        assertThat(countDummies(SHARD_A)).isEqualTo(beforeA);
        assertThat(countDummies(SHARD_B)).isEqualTo(beforeB);
    }

    // --- Effective shard fallback tests (unregistered shard → default) ---

    @Test
    void should_add_unregistered_shard_to_default_shard() {
        // GIVEN — a dummy with UUID targeting unregistered shard (2,0)
        var dummy = createShardedDummy(UNREGISTERED_SHARD);

        // WHEN
        repository.add(dummy);

        // THEN — findable via the sharded repo
        assertThat(repository.findById(dummy.id.getValue())).isPresent();

        // AND — physically stored on default shard (SHARD_A), not on SHARD_B
        var shardBOnlyRepo = createRepository(singleShardRegistry(SHARD_B));
        assertThat(shardBOnlyRepo.findById(dummy.id.getValue())).isEmpty();
    }

    @Test
    void should_add_unregistered_shard_in_transaction_to_default_shard() {
        // GIVEN
        var dummy = createShardedDummy(UNREGISTERED_SHARD);
        var beforeA = countDummies(SHARD_A);

        // WHEN — transaction on default shard
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.add(dummy);
        });

        // THEN — one new dummy on default shard
        assertThat(countDummies(SHARD_A)).isEqualTo(beforeA + 1);
        assertThat(repository.findById(dummy.id.getValue())).isPresent();
    }

    @Test
    void should_update_unregistered_shard_on_default_shard() {
        // GIVEN
        var dummy = createShardedDummy(UNREGISTERED_SHARD);
        repository.add(dummy);

        // WHEN
        var updated = dummy.deposit(BigDecimal.valueOf(25));
        databaseRegistry.transactionManager(SHARD_A).inTransaction(_ -> {
            repository.update(updated);
        });

        // THEN
        var fetched = repository.findById(dummy.id.getValue());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().balance).isEqualByComparingTo(BigDecimal.valueOf(35)); // 10 + 25
    }

    @Test
    void should_findById_unregistered_shard_from_default_shard() {
        // GIVEN — dummies on default, SHARD_B, and unregistered shard
        var dummyDefault = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        var dummyUnregistered = createShardedDummy(UNREGISTERED_SHARD);
        repository.add(dummyDefault);
        repository.add(dummyB);
        repository.add(dummyUnregistered);

        // WHEN / THEN — all findable
        assertThat(repository.findById(dummyDefault.id.getValue())).isPresent();
        assertThat(repository.findById(dummyB.id.getValue())).isPresent();
        assertThat(repository.findById(dummyUnregistered.id.getValue())).isPresent();
    }

    @Test
    void should_existsById_unregistered_shard_on_default_shard() {
        // GIVEN
        var dummy = createShardedDummy(UNREGISTERED_SHARD);
        repository.add(dummy);

        // WHEN / THEN
        assertThat(repository.existsById(dummy.id.getValue())).isTrue();
    }

    @Test
    void should_findAllByIds_with_unregistered_shard() {
        // GIVEN — dummies across registered and unregistered shards
        var dummyA = createShardedDummy(SHARD_A);
        var dummyB = createShardedDummy(SHARD_B);
        var dummyUnregistered = createShardedDummy(UNREGISTERED_SHARD);
        repository.add(dummyA);
        repository.add(dummyB);
        repository.add(dummyUnregistered);

        // WHEN
        var found = repository.findAllByIds(
                List.of(dummyA.id.getValue(), dummyB.id.getValue(), dummyUnregistered.id.getValue()));

        // THEN — all three found
        assertThat(found).hasSize(3);
        assertThat(found).extracting(d -> d.id).containsExactlyInAnyOrder(dummyA.id, dummyB.id, dummyUnregistered.id);
    }

    @Test
    void should_findAll_includes_unregistered_shard_data() {
        // GIVEN
        var dummyA = createShardedDummy(SHARD_A, "NOK");
        var dummyB = createShardedDummy(SHARD_B, "NOK");
        var dummyUnregistered = createShardedDummy(UNREGISTERED_SHARD, "NOK");
        repository.add(dummyA);
        repository.add(dummyB);
        repository.add(dummyUnregistered);

        // WHEN
        var nokDummies = repository.findAllWhere(DSL.field("currency").eq("NOK"));

        // THEN — all three found (unregistered shard data lives on default)
        assertThat(nokDummies).extracting(d -> d.id).contains(dummyA.id, dummyB.id, dummyUnregistered.id);
    }

    @Test
    void should_count_includes_unregistered_shard_data() {
        // GIVEN
        var beforeCount = repository.count();
        repository.add(createShardedDummy(SHARD_A));
        repository.add(createShardedDummy(SHARD_B));
        repository.add(createShardedDummy(UNREGISTERED_SHARD));

        // WHEN / THEN — all three counted
        assertThat(repository.count()).isEqualTo(beforeCount + 3);
    }

    @Test
    void should_addAll_unregistered_shard_to_default_shard() {
        // GIVEN — two dummies on unregistered shard
        var dummy1 = createShardedDummy(UNREGISTERED_SHARD);
        var dummy2 = createShardedDummy(UNREGISTERED_SHARD);
        var beforeA = countDummies(SHARD_A);

        // WHEN
        repository.addAll(List.of(dummy1, dummy2));

        // THEN — both on default shard
        assertThat(countDummies(SHARD_A)).isEqualTo(beforeA + 2);
        assertThat(repository.findById(dummy1.id.getValue())).isPresent();
        assertThat(repository.findById(dummy2.id.getValue())).isPresent();
    }

    @Test
    void should_reject_batch_mixing_registered_and_unregistered_shards() {
        // GIVEN — one on SHARD_B, one on unregistered (falls back to SHARD_A)
        var dummyB = createShardedDummy(SHARD_B);
        var dummyUnregistered = createShardedDummy(UNREGISTERED_SHARD);

        // WHEN / THEN — different effective shards → CrossShardException
        assertThatThrownBy(() -> repository.addAll(List.of(dummyB, dummyUnregistered)))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void should_allow_batch_mixing_default_and_unregistered_shards() {
        // GIVEN — one on SHARD_A (default), one on unregistered (also falls back to default)
        var dummyA = createShardedDummy(SHARD_A);
        var dummyUnregistered = createShardedDummy(UNREGISTERED_SHARD);

        // WHEN — both resolve to same effective shard (default) → no exception
        repository.addAll(List.of(dummyA, dummyUnregistered));

        // THEN
        assertThat(repository.findById(dummyA.id.getValue())).isPresent();
        assertThat(repository.findById(dummyUnregistered.id.getValue())).isPresent();
    }

    // Subclasses implement this to create a DummyRepository for a specific DatabaseRegistry
    protected abstract AbstractRepository<Dummy, ?, ?, UUID> createRepository(DatabaseRegistry registry);

    private DatabaseRegistry singleShardRegistry(ShardIdentifier shard) {
        var tm = databaseRegistry.transactionManager(shard);
        return databaseRegistry().withDatabase(tm).build();
    }

    private Dummy createShardedDummy(ShardIdentifier shard) {
        return createShardedDummy(shard, "EUR");
    }

    private Dummy createShardedDummy(ShardIdentifier shard, String currencyCode) {
        var shardedUuid = ShardedUUID.generate(shard);
        var id = Id.of(Dummy.class, shardedUuid.value());
        var ownerId = randomUUID();
        var currency = Currency.getInstance(currencyCode);
        return dummy().id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(BigDecimal.TEN)
                .createdDate(Instant.now())
                .withInitialVersion()
                .withEvent(new DummyCreatedEvent(id, ownerId, currency, BigDecimal.TEN))
                .build();
    }

    private long countDummies(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard).selectCount().from("dummies").fetchOne(0, long.class);
    }
}
