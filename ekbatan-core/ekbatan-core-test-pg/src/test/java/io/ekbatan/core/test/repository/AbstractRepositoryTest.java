package io.ekbatan.core.test.repository;

import static io.ekbatan.core.test.generated.jooq.tables.Dummies.DUMMIES;
import static io.ekbatan.core.test.model.Dummy.createDummy;
import static java.lang.String.format;
import static java.util.UUID.randomUUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.MicroType;
import io.ekbatan.core.repository.exception.EntityNotFoundException;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.test.model.Dummy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbstractRepositoryTest extends BaseRepositoryTest {

    private DummyRepository dummyRepository;

    @BeforeEach
    void setUp() {
        dummyRepository = new DummyRepository(transactionManager);
    }

    @Test
    void should_add_correctly() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        // WHEN
        final var added = dummyRepository.add(dummy);
        final var found = dummyRepository.findById(dummy.getId().getValue());

        // THEN
        assertThat(added).isNotNull();
        assertThat(added.id).isEqualTo(dummy.id);
        assertThat(added.state).isEqualTo(dummy.state);
        assertThat(added.ownerId).isEqualTo(dummy.ownerId);
        assertThat(added.currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(added.balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(added.createdDate).isEqualTo(dummy.createdDate);
        assertThat(added.updatedDate).isEqualTo(dummy.updatedDate);
        assertThat(added.version).isEqualTo(1L);

        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(found.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(found.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(found.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(found.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(found.orElseThrow().createdDate).isEqualTo(dummy.createdDate);
        assertThat(found.orElseThrow().updatedDate).isEqualTo(dummy.updatedDate);
        assertThat(found.orElseThrow().version).isEqualTo(1L);
    }

    @Test
    void should_add_correctly_in_transaction() {
        // GIVEN / WHEN

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        transactionManager.inTransaction(_ -> {
            dummyRepository.add(dummy);
        });

        // THEN

        final var fetchedDummy = dummyRepository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedDummy.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(fetchedDummy.orElseThrow().createdDate).isEqualTo(dummy.createdDate);
        assertThat(fetchedDummy.orElseThrow().updatedDate).isEqualTo(dummy.updatedDate);
    }

    @Test
    void should_rollback_add_in_transaction_upon_exception() {
        // GIVEN / WHEN

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.add(dummy);

                throw new RuntimeException();
            });
        } catch (Exception _) {

        }

        // THEN

        final var fetchedDummy = dummyRepository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isEmpty();
    }

    @Test
    void should_addNoResult() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        // WHEN
        dummyRepository.addNoResult(dummy);

        // THEN
        final var fetchedDummy = dummyRepository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedDummy.orElseThrow().balance).isEqualByComparingTo(BigDecimal.TEN);
    }

    @Test
    void should_addNoResult_inTransaction() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(100))
                .build();

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.addNoResult(dummy);
        });

        // THEN
        final var fetchedDummy = dummyRepository.findById(dummy.getId().getValue());
        assertThat(fetchedDummy).isPresent();
        assertThat(fetchedDummy.orElseThrow().id).isEqualTo(dummy.id);
        assertThat(fetchedDummy.orElseThrow().state).isEqualTo(dummy.state);
        assertThat(fetchedDummy.orElseThrow().ownerId).isEqualTo(dummy.ownerId);
        assertThat(fetchedDummy.orElseThrow().currency.getCurrencyCode()).isEqualTo("USD");
        assertThat(fetchedDummy.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void should_addAll() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }

        // WHEN
        final var addedDummies = dummyRepository.addAll(dummies);

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
        assertThat(fetchDummies).hasSameElementsAs(addedDummies);
    }

    @Test
    void should_addAll_inTransaction() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.addAll(dummies);
        });

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
    }

    @Test
    void should_rollback_addAll_inTransaction_when_exception() {

        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.addAll(dummies);
                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).hasSize(0);
    }

    @Test
    void should_addAllNoResult() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)))
                    .build());
        }

        // WHEN
        dummyRepository.addAllNoResult(dummies);

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).hasSize(10);
        assertThat(fetchDummies).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.TEN);
        });
    }

    @Test
    void should_addAllNoResult_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(
                            randomUUID(), Currency.getInstance(i % 2 == 0 ? "GBP" : "CHF"), BigDecimal.valueOf(100 + i))
                    .build());
        }

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.addAllNoResult(dummies);
        });

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).hasSize(5);
        assertThat(fetchDummies).allSatisfy(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("GBP", "CHF");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(100));
        });
    }

    @Test
    void should_rollback_addAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("JPY"), BigDecimal.valueOf(1000 + i))
                    .build());
        }

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.addAllNoResult(dummies);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchDummies).isEmpty();
    }

    @Test
    void should_update() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        final var updatedDummy = dummyRepository.update(dummyToUpdate);

        // THEN
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());

        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);

        assertThat(updatedDummy).isNotNull();
        assertThat(updatedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(updatedDummy.version).isEqualTo(2);
    }

    @Test
    void should_update_inTransaction() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.update(dummyToUpdate);
        });

        // THEN
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_not_update_inTransaction_when_exception() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.update(dummyToUpdate);

                throw new RuntimeException();
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummy.balance);
        assertThat(fetchedDummy.version).isEqualTo(1);
    }

    @Test
    void should_updateNoResult() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TWO);

        // WHEN
        dummyRepository.updateNoResult(dummyToUpdate);

        // THEN
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(BigDecimal.valueOf(8));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_updateNoResult_inTransaction() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("USD"), new BigDecimal("100.50"))
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(new BigDecimal("20.25"));

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.updateNoResult(dummyToUpdate);
        });

        // THEN
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(new BigDecimal("80.25"));
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_rollback_updateNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("GBP"), new BigDecimal("50.00"))
                .build();
        dummyRepository.add(dummy);
        final var dummyToUpdate = dummy.withdraw(BigDecimal.TEN);

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.updateNoResult(dummyToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN - Verify the update was rolled back
        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummy.balance);
        assertThat(fetchedDummy.version).isEqualTo(1);
    }

    @Test
    void should_throw_StaleRecordException_when_updating_stale_version() {
        // GIVEN
        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        final var dummyToUpdate = dummy.withdraw(BigDecimal.ONE);
        final var dummyToUpdateStale = dummy.withdraw(BigDecimal.TWO);
        dummyRepository.updateNoResult(dummyToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> dummyRepository.updateNoResult(dummyToUpdateStale))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("was concurrently modified or not found");

        final var fetchedDummy = dummyRepository.getById(dummy.getId().getValue());
        assertThat(fetchedDummy).isNotNull();
        assertThat(fetchedDummy.balance).isEqualByComparingTo(dummyToUpdate.balance);
        assertThat(fetchedDummy.version).isEqualTo(2);
    }

    @Test
    void should_updateAll() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        final var updatedDummies = dummyRepository.updateAll(dummiesToUpdate);

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());
        assertThat(fetchedDummies).hasSize(5);
        assertThat(fetchedDummies).hasSameElementsAs(updatedDummies);

        assertThat(updatedDummies).hasSize(5);
        updatedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAll_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.updateAll(dummiesToUpdate);
        });

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(BigDecimal.valueOf(9));
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_throw_optimistic_lock_exception_when_updating_stale_versions() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN)
                .build();
        dummyRepository.addAll(List.of(dummy1, dummy2));

        final var dummy1Changed = dummy1.withdraw(BigDecimal.ONE);
        dummyRepository.update(dummy1Changed);

        final var dummy1StaleChanged =
                dummy1.withdraw(BigDecimal.ONE); // This will have version 0 but current version is 1
        final var dummy2Changed = dummy2.withdraw(BigDecimal.ONE);

        // WHEN / THEN
        assertThatThrownBy(() -> dummyRepository.updateAll(List.of(dummy2Changed, dummy1StaleChanged)))
                .isInstanceOf(StaleRecordException.class)
                .hasMessageContaining("Expected to update 2 records but only updated 1");

        final var fetchedDummies = dummyRepository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy2.getId().getValue()));

        assertThat(fetchedDummies).hasSize(2);

        final var dummy1Fetched =
                fetchedDummies.stream().filter(w -> w.id.equals(dummy1.id)).findFirst();
        assertThat(dummy1Fetched).isPresent();
        assertThat(dummy1Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(dummy1Fetched.orElseThrow().version).isEqualTo(2);

        final var dummy2Fetched =
                fetchedDummies.stream().filter(w -> w.id.equals(dummy2.id)).findFirst();
        assertThat(dummy2Fetched).isPresent();
        assertThat(dummy2Fetched.orElseThrow().balance).isEqualByComparingTo(BigDecimal.valueOf(9));
        assertThat(dummy2Fetched.orElseThrow().version).isEqualTo(2);
    }

    @Test
    void should_rollback_updateAll_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var originalBalances = dummies.stream().collect(Collectors.toMap(w -> w.id, w -> w.balance));

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.updateAll(dummiesToUpdate);
                throw new RuntimeException("Test exception");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w.balance).isEqualByComparingTo(originalBalances.get(w.id));
            assertThat(w.version).isOne();
        });
    }

    @Test
    void should_updateAllNoResult() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 5; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance(i % 2 == 0 ? "EUR" : "USD"),
                            BigDecimal.TEN.add(BigDecimal.valueOf(i)))
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.ONE)).toList();

        // WHEN
        dummyRepository.updateAllNoResult(dummiesToUpdate);

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchedDummies).hasSize(5);
        fetchedDummies.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isIn("EUR", "USD");
            assertThat(w.balance).isGreaterThanOrEqualTo(BigDecimal.valueOf(9)); // 10 - 1, 11 - 1, etc.
            assertThat(w.version).isEqualTo(2);
        });
    }

    @Test
    void should_updateAllNoResult_inTransaction() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 3; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance("GBP"),
                            new BigDecimal("100.00").add(BigDecimal.valueOf(i * 10)))
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(new BigDecimal("5.50"))).toList();

        // WHEN
        transactionManager.inTransaction(_ -> {
            dummyRepository.updateAllNoResult(dummiesToUpdate);
        });

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchedDummies).hasSize(3);
        fetchedDummies.forEach(w -> {
            assertThat(w).isNotNull();
            assertThat(w.currency.getCurrencyCode()).isEqualTo("GBP");
            assertThat(w.version).isEqualTo(2);
        });
        assertThat(fetchedDummies.get(0).balance).isEqualByComparingTo(new BigDecimal("94.50")); // 100.00 - 5.50
        assertThat(fetchedDummies.get(1).balance).isEqualByComparingTo(new BigDecimal("104.50"));
        assertThat(fetchedDummies.get(2).balance).isEqualByComparingTo(new BigDecimal("114.50"));
    }

    @Test
    void should_rollback_updateAllNoResult_inTransaction_when_exception() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 4; i++) {
            dummies.add(createDummy(
                            randomUUID(),
                            Currency.getInstance("JPY"),
                            new BigDecimal("1000").add(BigDecimal.valueOf(i * 100)))
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(new BigDecimal("100"))).toList();

        // WHEN
        try {
            transactionManager.inTransaction((Consumer<DSLContext>) _ -> {
                dummyRepository.updateAllNoResult(dummiesToUpdate);
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (Exception _) {
        }

        // THEN
        final var fetchedDummies = dummyRepository.findAllByIds(
                dummies.stream().map(Dummy::getId).map(MicroType::getValue).toList());

        assertThat(fetchedDummies).hasSize(4);
        fetchedDummies.forEach(w -> {
            assertThat(w.version).isEqualTo(1);
            assertThat(w.balance).isGreaterThanOrEqualTo(new BigDecimal("1000"));
        });
    }

    @Test
    void should_throw_StaleRecordException_when_updatingAll_with_stale_versions() {
        // GIVEN
        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 3; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), new BigDecimal("50.00"))
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var dummiesToUpdate =
                dummies.stream().map(w -> w.withdraw(BigDecimal.TEN)).toList();

        final var firstDummyToUpdate = dummiesToUpdate.getFirst();
        dummyRepository.updateNoResult(firstDummyToUpdate);

        // WHEN & THEN
        assertThatThrownBy(() -> dummyRepository.updateAllNoResult(dummiesToUpdate))
                .isInstanceOf(StaleRecordException.class);
    }

    @Test
    void should_findById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummy = dummyRepository.add(dummy);

        // WHEN
        final var found = dummyRepository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).isEqualTo(dummy);
    }

    @Test
    void should_return_empty_when_findById_with_non_existent_id() {
        // WHEN
        final var found = dummyRepository.findById(randomUUID());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_return_empty_when_findById_when_marked_as_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        final var deletedDummy = dummy.delete();
        dummyRepository.update(deletedDummy);

        // WHEN
        final var found = dummyRepository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_getById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummy = dummyRepository.add(dummy);

        // WHEN
        final var found = dummyRepository.getById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEqualTo(dummy);
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_with_non_existent_id() {
        // GIVEN
        final var id = randomUUID();

        // WHEN / THEN
        assertThatThrownBy(() -> dummyRepository.getById(id))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(format("No entity found with id: %s[id=%s]", Dummy.class.getSimpleName(), id));
    }

    @Test
    void should_throw_EntityNotFoundException_when_getById_when_marked_as_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        final var deletedDummy = dummy.delete();
        dummyRepository.update(deletedDummy);

        // WHEN
        final var found = dummyRepository.findById(dummy.getId().getValue());

        // THEN
        assertThat(found).isEmpty();
    }

    @Test
    void should_findAllByIds() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE)
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3));

        // WHEN
        final var foundDummies = dummyRepository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy3.getId().getValue(), randomUUID()));

        // THEN
        assertThat(foundDummies).hasSize(2);
        assertThat(foundDummies).extracting(w -> w.id).containsExactlyInAnyOrder(dummy1.id, dummy3.id);
    }

    @Test
    void findAllByIds_should_return_non_DELETED_items() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2));

        final var deletedDummy2 = dummy2.delete();
        dummyRepository.update(deletedDummy2);

        // WHEN
        final var foundDummies = dummyRepository.findAllByIds(
                List.of(dummy1.getId().getValue(), dummy2.getId().getValue()));

        // THEN
        assertThat(foundDummies).hasSize(1);
        assertThat(foundDummies).extracting(w -> w.id).containsExactly(dummy1.id);
    }

    @Test
    void should_return_empty_list_when_findAllByIds_with_empty_input() {
        // WHEN
        final var foundDummies = dummyRepository.findAllByIds(List.of());

        // THEN
        assertThat(foundDummies).isEmpty();
    }

    @Test
    void should_findAll_with_offset_and_limit_and_sortFields() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(i))
                    .build();
            dummies.add(dummy);
        }
        dummyRepository.addAll(dummies);

        // WHEN
        final var result = dummyRepository.findAll(2, 5, DUMMIES.CREATED_DATE.asc());

        // THEN
        assertThat(result).hasSize(5);
        assertThat(result).containsExactlyElementsOf(dummies.subList(2, 7));
    }

    @Test
    void should_findAll_with_offset_and_limit_and_sortFields_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            dummies.add(createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(i))
                    .build());
        }
        dummyRepository.addAll(dummies);

        final var deletedDummies = List.of(
                dummies.get(2).delete(), dummies.get(5).delete(), dummies.get(8).delete());
        dummyRepository.updateAll(deletedDummies);

        // WHEN
        // Active dummies indices: 0, 1, 3, 4, 6, 7, 9 (Total 7)
        final var result = dummyRepository.findAll(2, 3, DUMMIES.BALANCE.asc());

        // THEN
        // Offset 2 skips (0, 1). Limit 3 takes (3, 4, 6).
        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(w -> w.id)
                .containsExactly(dummies.get(3).id, dummies.get(4).id, dummies.get(6).id);
    }

    @Test
    void should_findAll() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE)
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO)
                .build();
        final var dummy4 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.ZERO)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3, dummy4));

        final var deletedDummy3 = dummy3.delete();
        dummyRepository.update(deletedDummy3);

        // WHEN
        final var result = dummyRepository.findAll();

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result).extracting(w -> w.id).containsExactlyInAnyOrder(dummy1.id, dummy2.id, dummy4.id);
    }

    @Test
    void should_findAll_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.ONE)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2));

        final var deletedDummy1 = dummy1.delete();
        dummyRepository.update(deletedDummy1);

        // WHEN
        final var result = dummyRepository.findAll();

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(dummy2.id);
    }

    @Test
    void should_findAllWhere_with_condition_and_sort_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(20))
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(30))
                .build();
        final var dummy4 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(40))
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3, dummy4));

        final var deletedDummy2 = dummy2.delete();
        dummyRepository.update(deletedDummy2);

        // WHEN
        final var result = dummyRepository.findAllWhere(DUMMIES.CURRENCY.eq("EUR"), DUMMIES.BALANCE.desc());

        // THEN
        assertThat(result).hasSize(2);
        assertThat(result).extracting(w -> w.id).containsExactly(dummy3.id, dummy1.id);
    }

    @Test
    void should_findAllWhere_with_condition_offset_limit_and_sort_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            // Dummies 0-7, 9 are EUR. Dummy 8 is USD.
            String currencyCode = (i == 8) ? "USD" : "EUR";
            dummies.add(createDummy(randomUUID(), Currency.getInstance(currencyCode), BigDecimal.valueOf(i))
                    .build());
        }
        dummyRepository.addAll(dummies);

        // Delete Dummy 2 and Dummy 5 (both are EUR)
        final var deletedDummies =
                List.of(dummies.get(2).delete(), dummies.get(5).delete());
        dummyRepository.updateAll(deletedDummies);

        // WHEN
        // Active EUR indices sorted by balance: 0, 1, 3, 4, 6, 7, 9
        final var result = dummyRepository.findAllWhere(DUMMIES.CURRENCY.eq("EUR"), 2, 3, DUMMIES.BALANCE.asc());

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(w -> w.id)
                .containsExactly(dummies.get(3).id, dummies.get(4).id, dummies.get(6).id);
    }

    @Test
    void should_existsById() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummy = dummyRepository.add(dummy);

        // WHEN
        final var exists = dummyRepository.existsById(dummy.getId().getValue());
        final var notExists = dummyRepository.existsById(randomUUID());

        // THEN
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    void should_existsById_excludes_deleted() {
        // GIVEN
        var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        final var deletedDummy = dummy.delete();
        dummyRepository.update(deletedDummy);

        // WHEN
        final var exists = dummyRepository.existsById(dummy.getId().getValue());

        // THEN
        assertThat(exists).isFalse();
    }

    @Test
    void should_count_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN)
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("GBP"), BigDecimal.TEN)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3));

        dummyRepository.update(dummy2.delete());

        // WHEN
        final var count = dummyRepository.count();

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_countWhere_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy4 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3, dummy4));

        dummyRepository.update(dummy3.delete());

        // WHEN
        final var count = dummyRepository.countWhere(DUMMIES.CURRENCY.eq("EUR"));

        // THEN
        assertThat(count).isEqualTo(2);
    }

    @Test
    void should_findOneWhere_excludes_deleted() {
        // GIVEN
        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.TEN)
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2));

        dummyRepository.update(dummy1.delete());

        // WHEN
        final var foundDeleted =
                dummyRepository.findOneWhere(DUMMIES.ID.eq(dummy1.getId().getValue()));
        final var foundActive =
                dummyRepository.findOneWhere(DUMMIES.ID.eq(dummy2.getId().getValue()));

        // THEN
        assertThat(foundDeleted).isEmpty();
        assertThat(foundActive).isPresent();
        assertThat(foundActive.get().id).isEqualTo(dummy2.id);
    }

    @Test
    void should_findAllWhere_with_condition_offset_limit_and_collection_sort_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummies = new ArrayList<Dummy>();
        for (int i = 0; i < 10; i++) {
            // Dummies 0-7, 9 are EUR. Dummy 8 is USD.
            String currencyCode = (i == 8) ? "USD" : "EUR";
            dummies.add(createDummy(randomUUID(), Currency.getInstance(currencyCode), BigDecimal.valueOf(i))
                    .build());
        }
        dummyRepository.addAll(dummies);

        // Delete Dummy 2 and Dummy 5 (both are EUR)
        final var deletedDummies =
                List.of(dummies.get(2).delete(), dummies.get(5).delete());
        dummyRepository.updateAll(deletedDummies);

        // WHEN
        // Active EUR indices sorted by balance: 0, 1, 3, 4, 6, 7, 9
        final var result =
                dummyRepository.findAllWhere(DUMMIES.CURRENCY.eq("EUR"), 2, 3, List.of(DUMMIES.BALANCE.asc()));

        // THEN
        assertThat(result).hasSize(3);
        assertThat(result)
                .extracting(w -> w.id)
                .containsExactly(dummies.get(3).id, dummies.get(4).id, dummies.get(6).id);
    }

    @Test
    void should_findAllWhere_condition_excludes_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy1 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        final var dummy2 = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.valueOf(20))
                .build();
        final var dummy3 = createDummy(randomUUID(), Currency.getInstance("USD"), BigDecimal.valueOf(30))
                .build();

        dummyRepository.addAll(List.of(dummy1, dummy2, dummy3));

        dummyRepository.update(dummy1.delete());

        // WHEN
        final var result = dummyRepository.findAllWhere(DUMMIES.CURRENCY.eq("EUR"));

        // THEN
        assertThat(result).hasSize(1);
        assertThat(result).extracting(w -> w.id).containsExactly(dummy2.id);
    }

    @Test
    void should_existsWhere_returns_true_when_exists() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        // WHEN
        final var exists = dummyRepository.existsWhere(DUMMIES.CURRENCY.eq("EUR"));

        // THEN
        assertThat(exists).isTrue();
    }

    @Test
    void should_existsWhere_returns_false_when_deleted() {
        // GIVEN
        final var dsl = DSL.using(transactionManager.primaryConnectionProvider.getDataSource(), SQLDialect.POSTGRES);
        dsl.truncate(DUMMIES).cascade().execute();

        final var dummy = createDummy(randomUUID(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();
        dummyRepository.add(dummy);

        dummyRepository.update(dummy.delete());

        // WHEN
        final var exists = dummyRepository.existsWhere(DUMMIES.CURRENCY.eq("EUR"));

        // THEN
        assertThat(exists).isFalse();
    }
}
