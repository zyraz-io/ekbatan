package io.ekbatan.core.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.time.VirtualClock;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

@Tag("tracing")
class ActionExecutorTracingTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    // --- Test setup ---

    private DatabaseRegistry databaseRegistry;
    private VirtualClock clock;

    @BeforeEach
    void setUp() throws Exception {
        var mockPrimaryProvider = mock(ConnectionProvider.class);
        var mockSecondaryProvider = mock(ConnectionProvider.class);
        var mockDataSource = mock(HikariDataSource.class);
        when(mockPrimaryProvider.getDataSource()).thenReturn(mockDataSource);
        when(mockSecondaryProvider.getDataSource()).thenReturn(mockDataSource);
        var transactionManager =
                spy(new TransactionManager(mockPrimaryProvider, mockSecondaryProvider, SQLDialect.POSTGRES));
        databaseRegistry = databaseRegistry().withDatabase(transactionManager).build();
        clock = new VirtualClock();

        doAnswer(invocation -> {
                    TransactionManager.CheckedConsumer<DSLContext> consumer = invocation.getArgument(0);
                    consumer.accept(null);
                    return null;
                })
                .when(transactionManager)
                .inTransactionChecked(ArgumentMatchers.<TransactionManager.CheckedConsumer<DSLContext>>any());
    }

    private ActionExecutor buildExecutor(ActionRegistry actionRegistry) {
        return actionExecutor()
                .namespace("test.namespace")
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Item.class, new RecordingRepository())
                        .build())
                .actionRegistry(actionRegistry)
                .eventPersister(new RecordingEventPersister())
                .clock(clock)
                .build();
    }

    // --- Helpers ---

    private SpanData findSpan(String name) {
        return otelTesting.getSpans().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No span found with name: " + name));
    }

    private List<SpanData> findSpans(String name) {
        return otelTesting.getSpans().stream()
                .filter(s -> s.getName().equals(name))
                .toList();
    }

    // --- Tests ---

    @Test
    void successful_action_creates_execute_span_with_attributes() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "test-user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.action.name")))
                .isEqualTo("CreateItemAction");
        assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.action.principal")))
                .isEqualTo("test-user");
        assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.action.outcome")))
                .isEqualTo("success");
        assertThat(actionSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void failed_action_records_error_on_execute_span() {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(AlwaysFailingAction.class, () -> new AlwaysFailingAction(clock))
                .build());

        // WHEN
        assertThatThrownBy(
                () -> executor.execute(() -> "user", AlwaysFailingAction.class, new AlwaysFailingAction.Params()));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.action.outcome")))
                .isEqualTo("error");
        assertThat(actionSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(actionSpan.getEvents()).anyMatch(event -> event.getName().equals("exception"));
    }

    @Test
    void perform_span_is_child_of_execute_span() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        var performSpan = findSpan("ekbatan.action.perform");
        assertThat(performSpan.getParentSpanId()).isEqualTo(actionSpan.getSpanId());
    }

    @Test
    void persist_span_is_child_of_execute_span() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        var persistSpan = findSpan("ekbatan.action.persist");
        assertThat(persistSpan.getParentSpanId()).isEqualTo(actionSpan.getSpanId());
    }

    @Test
    void all_spans_share_same_trace_id() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var traceId = findSpan("ekbatan.action.execute").getTraceId();
        assertThat(findSpan("ekbatan.action.perform").getTraceId()).isEqualTo(traceId);
        assertThat(findSpan("ekbatan.action.persist").getTraceId()).isEqualTo(traceId);
    }

    @Test
    void retry_records_events_and_count_on_action_span() throws Exception {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var executor = buildExecutor(actionRegistry()
                .withAction(FailingItemAction.class, () -> new FailingItemAction(clock, attempts))
                .build());

        // WHEN
        executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(1));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");

        // AND — retry event recorded
        var retryEvents = actionSpan.getEvents().stream()
                .filter(e -> e.getName().equals("retry"))
                .toList();
        assertThat(retryEvents).hasSize(1);
        assertThat(retryEvents.getFirst().getAttributes().get(AttributeKey.longKey("retry.count")))
                .isEqualTo(1L);
        assertThat(retryEvents.getFirst().getAttributes().get(AttributeKey.stringKey("retry.exception")))
                .isEqualTo("StaleRecordException");

        // AND — retry count attribute
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(1L);
    }

    @Test
    void no_retry_records_zero_count() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(0L);

        // AND — no retry events
        var retryEvents = actionSpan.getEvents().stream()
                .filter(e -> e.getName().equals("retry"))
                .toList();
        assertThat(retryEvents).isEmpty();
    }

    @Test
    void exhausted_retries_still_record_count_and_events() {
        // GIVEN
        var attempts = new AtomicInteger(0);
        var config = ExecutionConfiguration.Builder.executionConfiguration()
                .withRetry(StaleRecordException.class, new RetryConfig(2, Duration.ZERO))
                .build();
        var executor = buildExecutor(actionRegistry()
                .withAction(FailingItemAction.class, () -> new FailingItemAction(clock, attempts))
                .build());

        // WHEN
        assertThatThrownBy(() ->
                executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(10), config));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(2L);

        // AND — 2 retry events
        var retryEvents = actionSpan.getEvents().stream()
                .filter(e -> e.getName().equals("retry"))
                .toList();
        assertThat(retryEvents).hasSize(2);
    }

    @Test
    void no_otel_sdk_does_not_affect_execution() throws Exception {
        // GIVEN / WHEN / THEN — this test class uses OpenTelemetryExtension which registers an SDK,
        // but the test validates that tracing doesn't break the action result
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, () -> new CreateItemAction(clock))
                .build());

        var result = executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));
        assertThat(result.name).isEqualTo("wallet");
    }

    // --- Test model ---

    static class ItemEvent extends ModelEvent<Item> {
        ItemEvent(Id<Item> id) {
            super(id.getValue().toString(), Item.class);
        }
    }

    static class Item extends Model<Item, Id<Item>, GenericState> {
        public final String name;

        Item(Builder builder) {
            super(builder);
            this.name = builder.name;
        }

        @Override
        public Builder copy() {
            return Builder.item().copyBase(this).name(name);
        }

        static Builder createItem(String name, Instant createdDate) {
            var id = Id.random(Item.class);
            return Builder.item()
                    .id(id)
                    .state(GenericState.ACTIVE)
                    .name(name)
                    .createdDate(createdDate)
                    .withInitialVersion()
                    .withEvent(new ItemEvent(id));
        }

        static class Builder extends Model.Builder<Id<Item>, Builder, Item, GenericState> {
            String name;

            static Builder item() {
                return new Builder();
            }

            Builder name(String name) {
                this.name = name;
                return self();
            }

            @Override
            public Item build() {
                return new Item(this);
            }
        }
    }

    // --- Test actions ---

    static class CreateItemAction extends Action<CreateItemAction.Params, Item> {
        record Params(String name) {}

        CreateItemAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            return plan.add(Item.createItem(params.name, clock.instant()).build());
        }
    }

    static class FailingItemAction extends Action<FailingItemAction.Params, Item> {
        private final AtomicInteger attempts;

        record Params(int failUntilAttempt) {}

        FailingItemAction(java.time.Clock clock, AtomicInteger attempts) {
            super(clock);
            this.attempts = attempts;
        }

        @Override
        protected Item perform(Principal principal, Params params) {
            if (attempts.incrementAndGet() <= params.failUntilAttempt) {
                throw new StaleRecordException("stale", null);
            }
            return plan.add(Item.createItem("recovered", clock.instant()).build());
        }
    }

    static class AlwaysFailingAction extends Action<AlwaysFailingAction.Params, Void> {
        record Params() {}

        AlwaysFailingAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Void perform(Principal principal, Params params) {
            throw new IllegalArgumentException("always fails");
        }
    }

    // --- Recording test doubles ---

    static class RecordingRepository implements Repository<Item> {
        final List<Item> added = new ArrayList<>();

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
            return model;
        }

        @Override
        public void updateNoResult(Item model) {}

        @Override
        public List<Item> updateAll(Collection<Item> models) {
            return List.copyOf(models);
        }

        @Override
        public void updateAllNoResult(Collection<Item> models) {}

        @Override
        public List<Item> findAll() {
            return List.of();
        }
    }

    static class RecordingEventPersister implements EventPersister {
        @Override
        public void persistActionEvents(
                String namespace,
                String actionName,
                Instant startedDate,
                Instant completionDate,
                Object actionParams,
                Collection<ModelEvent<?>> modelEvents,
                ShardIdentifier shard,
                java.util.UUID actionEventId) {}
    }
}
