package io.ekbatan.core.action.persister;

import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.time.VirtualClock;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangePersisterTest {

    // --- Test model ---

    static class ItemCreatedEvent extends ModelEvent<Item> {
        ItemCreatedEvent(Id<Item> id) {
            super(id.getValue().toString(), Item.class);
        }
    }

    static class Item extends Model<Item, Id<Item>, GenericState> {
        Item(Builder builder) {
            super(builder);
        }

        @Override
        public Builder copy() {
            return Builder.item().copyBase(this);
        }

        static Builder createItem(Instant createdDate) {
            var id = Id.random(Item.class);
            return Builder.item()
                    .id(id)
                    .state(GenericState.ACTIVE)
                    .createdDate(createdDate)
                    .withInitialVersion()
                    .withEvent(new ItemCreatedEvent(id));
        }

        static class Builder extends Model.Builder<Id<Item>, Builder, Item, GenericState> {
            static Builder item() {
                return new Builder();
            }

            @Override
            public Item build() {
                return new Item(this);
            }
        }
    }

    // --- Test action ---

    static class CreateItemAction extends Action<CreateItemAction.Params, Item> {
        record Params() {}

        CreateItemAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            return plan.add(Item.createItem(clock.instant()).build());
        }
    }

    static class UpdateItemAction extends Action<UpdateItemAction.Params, Item> {
        private final Item existing;

        record Params() {}

        UpdateItemAction(java.time.Clock clock, Item existing) {
            super(clock);
            this.existing = existing;
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            return plan.update(existing.copy().state(GenericState.DELETED).build());
        }
    }

    static class NoOpAction extends Action<NoOpAction.Params, Void> {
        record Params() {}

        NoOpAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Void perform(Principal principal, Params params) {
            return null;
        }
    }

    // --- Recording test doubles ---

    static class RecordingRepository implements Repository<Item> {
        final List<Item> added = new ArrayList<>();
        final List<Item> updated = new ArrayList<>();

        @Override
        public io.ekbatan.core.shard.ShardingStrategy<?> shardingStrategy() {
            return new io.ekbatan.core.shard.NoShardingStrategy<>();
        }

        @Override
        public Item add(Item model) {
            added.add(model);
            return model;
        }

        @Override
        public void addNoResult(Item model) {
            added.add(model);
        }

        @Override
        public List<Item> addAll(Collection<Item> models) {
            added.addAll(models);
            return List.copyOf(models);
        }

        @Override
        public void addAllNoResult(Collection<Item> models) {
            added.addAll(models);
        }

        @Override
        public Item update(Item model) {
            updated.add(model);
            return model;
        }

        @Override
        public void updateNoResult(Item model) {
            updated.add(model);
        }

        @Override
        public List<Item> updateAll(Collection<Item> models) {
            updated.addAll(models);
            return List.copyOf(models);
        }

        @Override
        public void updateAllNoResult(Collection<Item> models) {
            updated.addAll(models);
        }

        @Override
        public List<Item> findAll() {
            return List.of();
        }
    }

    static class RecordingEventPersister implements EventPersister {
        String actionName;
        Instant startedDate;
        Instant completionDate;
        Object actionParams;
        Collection<ModelEvent<?>> modelEvents;
        int callCount = 0;

        @Override
        public void persistActionEvents(
                String actionName,
                Instant startedDate,
                Instant completionDate,
                Object actionParams,
                Collection<ModelEvent<?>> modelEvents,
                io.ekbatan.core.shard.ShardIdentifier shard,
                java.util.UUID actionEventId) {
            this.actionName = actionName;
            this.startedDate = startedDate;
            this.completionDate = completionDate;
            this.actionParams = actionParams;
            this.modelEvents = modelEvents;
            this.callCount++;
        }
    }

    // --- Tests ---

    @Test
    void persist_addition_calls_repository_addAll() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        action.perform(() -> "user", new CreateItemAction.Params());

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                new CreateItemAction.Params(),
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(repo.added).hasSize(1);
        assertThat(repo.updated).isEmpty();
    }

    @Test
    void persist_update_calls_repository_updateAll() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var existing = Item.Builder.item()
                .id(Id.random(Item.class))
                .state(GenericState.ACTIVE)
                .createdDate(clock.instant())
                .withInitialVersion()
                .build();

        var action = new UpdateItemAction(clock, existing);
        action.perform(() -> "user", new UpdateItemAction.Params());

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                new UpdateItemAction.Params(),
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(repo.updated).hasSize(1);
        assertThat(repo.added).isEmpty();
    }

    @Test
    void persist_extracts_model_events_from_additions() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        action.perform(() -> "user", new CreateItemAction.Params());

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                new CreateItemAction.Params(),
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(eventPersister.modelEvents).hasSize(1);
        assertThat(eventPersister.modelEvents.iterator().next()).isInstanceOf(ItemCreatedEvent.class);
    }

    @Test
    void persist_passes_action_name_to_event_persister() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        action.perform(() -> "user", new CreateItemAction.Params());

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                new CreateItemAction.Params(),
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(eventPersister.actionName).isEqualTo("CreateItemAction");
    }

    @Test
    void persist_passes_start_and_completion_dates() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2025-06-01T12:00:00Z"));
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        action.perform(() -> "user", new CreateItemAction.Params());

        // WHEN
        var startDate = Instant.parse("2025-06-01T11:59:59Z");
        persister.persist(
                action.getClass().getSimpleName(),
                new CreateItemAction.Params(),
                startDate,
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(eventPersister.startedDate).isEqualTo(startDate);
        assertThat(eventPersister.completionDate).isEqualTo(Instant.parse("2025-06-01T12:00:00Z"));
    }

    @Test
    void persist_no_changes_still_calls_event_persister() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var eventPersister = new RecordingEventPersister();
        var registry = repositoryRegistry().build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new NoOpAction(clock);
        action.perform(() -> "user", new NoOpAction.Params());

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                new NoOpAction.Params(),
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(eventPersister.callCount).isEqualTo(1);
        assertThat(eventPersister.modelEvents).isEmpty();
        assertThat(eventPersister.actionName).isEqualTo("NoOpAction");
    }

    @Test
    void persist_throws_when_no_repository_for_type() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var eventPersister = new RecordingEventPersister();
        var registry = repositoryRegistry().build(); // no repo registered
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        action.perform(() -> "user", new CreateItemAction.Params());

        // WHEN / THEN
        assertThatThrownBy(() -> persister.persist(
                        action.getClass().getSimpleName(),
                        new CreateItemAction.Params(),
                        Instant.now(),
                        action.plan.changes(),
                        io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                        java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No repository found for entity type");
    }

    @Test
    void persist_passes_params_to_event_persister() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        var repo = new RecordingRepository();
        var eventPersister = new RecordingEventPersister();
        var registry =
                repositoryRegistry().withModelRepository(Item.class, repo).build();
        var persister = new ChangePersister(registry, eventPersister, clock);

        var action = new CreateItemAction(clock);
        var params = new CreateItemAction.Params();
        action.perform(() -> "user", params);

        // WHEN
        persister.persist(
                action.getClass().getSimpleName(),
                params,
                Instant.now(),
                action.plan.changes(),
                io.ekbatan.core.shard.ShardIdentifier.DEFAULT,
                java.util.UUID.randomUUID());

        // THEN
        assertThat(eventPersister.actionParams).isSameAs(params);
    }
}
