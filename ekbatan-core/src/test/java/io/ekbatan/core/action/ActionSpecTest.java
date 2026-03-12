package io.ekbatan.core.action;

import static io.ekbatan.core.action.TestModel.Builder.testModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.time.VirtualClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActionSpecTest {

    // --- assertAdded ---

    @Test
    void assertAdded_single_with_verifier() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2025-01-01T00:00:00Z"));

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("my-wallet"))
                .assertAdded(TestModel.class, model -> {
                    assertThat(model.name).isEqualTo("my-wallet");
                    assertThat(model.state).isEqualTo(TestState.ACTIVE);
                    assertThat(model.createdDate).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
                })
                .assertEmitted(TestCreatedEvent.class)
                .assertNoUpdates();
    }

    @Test
    void assertAdded_with_count() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertAdded(TestModel.class, 1);
    }

    @Test
    void assertAdded_with_count_and_verifier() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertAdded(TestModel.class, 1, models -> {
                    assertThat(models).hasSize(1);
                    assertThat(models.getFirst().name).isEqualTo("test");
                });
    }

    @Test
    void assertAdded_fails_when_count_mismatch() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertAdded(TestModel.class, 2))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 2 addition(s) of TestModel but found 1");
    }

    // --- assertUpdated ---

    @Test
    void assertUpdated_single_with_verifier() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("to-delete")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params())
                .assertUpdated(TestModel.class, model -> {
                    assertThat(model.state).isEqualTo(TestState.DELETED);
                })
                .assertEmitted(TestDeletedEvent.class)
                .assertNoAdditions();
    }

    @Test
    void assertUpdated_with_count() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params())
                .assertUpdated(TestModel.class, 1);
    }

    @Test
    void assertUpdated_fails_when_no_updates() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertUpdated(TestModel.class, 1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 1 update(s) of TestModel but found 0");
    }

    // --- assertEmitted ---

    @Test
    void assertEmitted_with_verifier() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("inspected"))
                .assertEmitted(TestCreatedEvent.class, event -> {
                    assertThat(event.name).isEqualTo("inspected");
                });
    }

    @Test
    void assertEmitted_with_count() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertEmitted(TestCreatedEvent.class, 1);
    }

    @Test
    void assertEmitted_with_count_and_verifier() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"))
                .assertEmitted(TestCreatedEvent.class, 1, events -> {
                    assertThat(events.getFirst().name).isEqualTo("model");
                });
    }

    @Test
    void assertEmitted_fails_when_no_events() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        var assert_ = ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params());

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertEmitted(TestCreatedEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected at least 1 event of TestCreatedEvent but none were emitted");
    }

    // --- assertNotEmitted ---

    @Test
    void assertNotEmitted_passes_when_event_absent() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertNotEmitted(TestDeletedEvent.class);
    }

    @Test
    void assertNotEmitted_fails_when_event_present() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertNotEmitted(TestCreatedEvent.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no events of TestCreatedEvent but found 1");
    }

    // --- assertNoAdditions / assertNoUpdates / assertNoEvents / assertNoChanges ---

    @Test
    void assertNoAdditions_passes_on_update_only() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params())
                .assertNoAdditions();
    }

    @Test
    void assertNoAdditions_fails_when_additions_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(assert_::assertNoAdditions)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no additions but found 1");
    }

    @Test
    void assertNoUpdates_passes_on_create_only() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertNoUpdates();
    }

    @Test
    void assertNoUpdates_fails_when_updates_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        var assert_ = ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params());

        // WHEN / THEN
        assertThatThrownBy(assert_::assertNoUpdates)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no updates but found 1");
    }

    @Test
    void assertNoEvents_passes_when_no_events() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("old-name")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new RenameAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new RenameAction.Params("new-name"))
                .assertNoEvents()
                .assertUpdated(TestModel.class, model -> {
                    assertThat(model.name).isEqualTo("new-name");
                });
    }

    @Test
    void assertNoEvents_fails_when_events_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(assert_::assertNoEvents)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no events but found 1");
    }

    @Test
    void assertNoChanges_passes_for_unrelated_type() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertNoChangesOf(TestProduct.class);
    }

    @Test
    void assertNoChanges_fails_when_changes_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"));

        // WHEN / THEN
        assertThatThrownBy(assert_::assertNoChanges).isInstanceOf(AssertionError.class);
    }

    // --- assertNoAdditionsOf / assertNoUpdatesOf / assertNoChangesOf ---

    @Test
    void assertNoAdditionsOf_passes_for_unrelated_type() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("test"))
                .assertNoAdditionsOf(TestProduct.class);
    }

    @Test
    void assertNoAdditionsOf_fails_when_additions_of_type_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertNoAdditionsOf(TestProduct.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no additions of TestProduct but found 1");
    }

    @Test
    void assertNoUpdatesOf_passes_for_unrelated_type() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params())
                .assertNoUpdatesOf(TestProduct.class);
    }

    @Test
    void assertNoChangesOf_passes_for_unrelated_type() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"))
                .assertAdded(TestModel.class, 1)
                .assertAdded(TestProduct.class, 1);
    }

    @Test
    void assertNoChangesOf_fails_when_changes_of_type_exist() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertNoChangesOf(TestModel.class))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected no additions of TestModel but found 1");
    }

    // --- assertTotalAdditions / assertTotalUpdates ---

    @Test
    void assertTotalAdditions_counts_across_types() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"))
                .assertTotalAdditions(2)
                .assertTotalUpdates(0);
    }

    @Test
    void assertTotalAdditions_fails_on_mismatch() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var assert_ = ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"));

        // WHEN / THEN
        assertThatThrownBy(() -> assert_.assertTotalAdditions(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected 1 total addition(s) but found 2");
    }

    @Test
    void assertTotalUpdates_counts_across_types() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN / THEN
        ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params())
                .assertTotalUpdates(1)
                .assertTotalAdditions(0);
    }

    // --- assertResult ---

    @Test
    void assertResult() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("result-check"))
                .assertResult(result -> {
                    assertThat(result.name).isEqualTo("result-check");
                    assertThat(result.version).isEqualTo(1L);
                });
    }

    // --- assertThrows ---

    @Test
    void assertThrows_passes_on_expected_exception() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new FailingAction(clock))
                .withPrincipal(() -> "test-user")
                .assertThrows(IllegalArgumentException.class, new FailingAction.Params("boom"));
    }

    @Test
    void assertThrows_with_verifier() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new FailingAction(clock))
                .withPrincipal(() -> "test-user")
                .assertThrows(IllegalArgumentException.class, new FailingAction.Params("boom"), ex -> {
                    assertThat(ex.getMessage()).isEqualTo("boom");
                });
    }

    @Test
    void assertThrows_fails_when_no_exception() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        assertThatThrownBy(() -> ActionSpec.of(new CreateAction(clock))
                        .withPrincipal(() -> "test-user")
                        .assertThrows(IllegalArgumentException.class, new CreateAction.Params("test")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected IllegalArgumentException to be thrown but nothing was thrown");
    }

    @Test
    void assertThrows_fails_when_wrong_exception_type() {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        assertThatThrownBy(() -> ActionSpec.of(new FailingAction(clock))
                        .withPrincipal(() -> "test-user")
                        .assertThrows(IllegalStateException.class, new FailingAction.Params("boom")))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Expected IllegalStateException but got IllegalArgumentException");
    }

    // --- Raw accessors ---

    @Test
    void result_returns_raw_value() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN
        var assert_ = ActionSpec.of(new CreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateAction.Params("raw"));

        // THEN
        TestModel result = assert_.result();
        assertThat(result.name).isEqualTo("raw");
    }

    @Test
    void additions_returns_list() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN
        var assert_ = ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("model", "SKU-001"));

        // THEN
        assertThat(assert_.additions(TestModel.class)).hasSize(1);
        assertThat(assert_.additions(TestProduct.class)).hasSize(1);
        assertThat(assert_.additions(TestProduct.class).getFirst().sku).isEqualTo("SKU-001");
    }

    @Test
    void updates_returns_list() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var existing = testModel()
                .id(Id.random(TestModel.class))
                .state(TestState.ACTIVE)
                .name("test")
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        // WHEN
        var assert_ = ActionSpec.of(new DeleteAction(clock, existing))
                .withPrincipal(() -> "admin")
                .execute(new DeleteAction.Params());

        // THEN
        assertThat(assert_.updates(TestModel.class)).hasSize(1);
        assertThat(assert_.updates(TestModel.class).getFirst().state).isEqualTo(TestState.DELETED);
        assertThat(assert_.updates(TestProduct.class)).isEmpty();
    }

    // --- Multi-type combined assertions ---

    @Test
    void createBoth_full_verification() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2025-06-01T12:00:00Z"));

        // WHEN / THEN
        ActionSpec.of(new CreateBothAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new CreateBothAction.Params("widget", "SKU-999"))
                .assertAdded(TestModel.class, model -> {
                    assertThat(model.name).isEqualTo("widget");
                    assertThat(model.createdDate).isEqualTo(Instant.parse("2025-06-01T12:00:00Z"));
                })
                .assertAdded(TestProduct.class, product -> {
                    assertThat(product.sku).isEqualTo("SKU-999");
                })
                .assertTotalAdditions(2)
                .assertTotalUpdates(0)
                .assertEmitted(TestCreatedEvent.class)
                .assertEmitted(TestProductCreatedEvent.class)
                .assertNotEmitted(TestDeletedEvent.class)
                .assertNoUpdates()
                .assertResult(result -> {
                    assertThat(result.name).isEqualTo("widget");
                });
    }
}
