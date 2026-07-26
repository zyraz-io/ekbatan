package io.ekbatan.core.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import io.ekbatan.core.action.persister.event.EventPersister;
import io.ekbatan.core.domain.GenericState;
import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.domain.ModelEvent;
import io.ekbatan.core.domain.Persistable;
import io.ekbatan.core.persistence.ConnectionProvider;
import io.ekbatan.core.persistence.TransactionManager;
import io.ekbatan.core.repository.Repository;
import io.ekbatan.core.repository.exception.StaleRecordException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.core.shard.ShardingStrategy;
import io.ekbatan.testsupport.time.VirtualClock;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.ObjectMapper;

// Mockito spy/mock isn't supported on GraalVM native image (closed-world model rejects
// runtime ByteBuddy subclass proxies). See ActionExecutorTest for the full upstream
// blocker chain (ASM 9.7.1 / JDK 25 / ClassFileAPI). OpenTelemetry itself is fine on
// native via the Reachability Metadata Repo - Mockito is the disqualifier here.
@DisabledInNativeImage
@Tag("tracing")
class ActionExecutorTracingTest {

    @RegisterExtension
    static final OpenTelemetryExtension otelTesting = OpenTelemetryExtension.create();

    /** Commits normally in the two-shard tests below. */
    private static final ShardIdentifier SHARD_A = ShardIdentifier.of(0, 0);

    /** Always fails in the two-shard tests below. */
    private static final ShardIdentifier SHARD_B = ShardIdentifier.of(1, 0);

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

    /**
     * Builds an executor over two shards: {@link #SHARD_A} commits, {@link #SHARD_B} always fails.
     * Items are routed by name prefix (see {@link NamePrefixShardingStrategy}), so a single action
     * can decide which shards it touches and in what order.
     */
    private ActionExecutor buildTwoShardExecutor(ActionRegistry actionRegistry) throws Exception {
        var shardA = spy(transactionManagerFor(SHARD_A));
        var shardB = spy(transactionManagerFor(SHARD_B));

        doAnswer(invocation -> {
                    TransactionManager.CheckedConsumer<DSLContext> consumer = invocation.getArgument(0);
                    consumer.accept(null);
                    return null;
                })
                .when(shardA)
                .inTransactionChecked(ArgumentMatchers.<TransactionManager.CheckedConsumer<DSLContext>>any());

        // IllegalStateException rather than StaleRecordException on purpose: the default retry
        // policy only replays StaleRecordException, and a replay would run the partial-commit
        // path a second time and make the span assertions ambiguous.
        doThrow(new IllegalStateException("shard B unavailable"))
                .when(shardB)
                .inTransactionChecked(ArgumentMatchers.<TransactionManager.CheckedConsumer<DSLContext>>any());

        return actionExecutor()
                .namespace("test.namespace")
                .databaseRegistry(databaseRegistry()
                        .withDefaultDatabase(shardA)
                        .withDatabase(shardB)
                        .build())
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry()
                        .withModelRepository(Item.class, new ShardRoutingRepository())
                        .build())
                .actionRegistry(actionRegistry)
                .eventPersister(new RecordingEventPersister())
                .clock(clock)
                .build();
    }

    private static TransactionManager transactionManagerFor(ShardIdentifier shard) {
        var primaryProvider = mock(ConnectionProvider.class);
        var secondaryProvider = mock(ConnectionProvider.class);
        var dataSource = mock(HikariDataSource.class);
        when(primaryProvider.getDataSource()).thenReturn(dataSource);
        when(secondaryProvider.getDataSource()).thenReturn(dataSource);
        return new TransactionManager(primaryProvider, secondaryProvider, SQLDialect.POSTGRES, shard);
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
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
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
                .withAction(AlwaysFailingAction.class, new AlwaysFailingAction(clock))
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
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
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
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
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
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
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
                .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                .build());

        // WHEN
        executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(1));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");

        // AND - retry event recorded
        var retryEvents = actionSpan.getEvents().stream()
                .filter(e -> e.getName().equals("retry"))
                .toList();
        assertThat(retryEvents).hasSize(1);
        assertThat(retryEvents.getFirst().getAttributes().get(AttributeKey.longKey("retry.count")))
                .isEqualTo(1L);
        assertThat(retryEvents.getFirst().getAttributes().get(AttributeKey.stringKey("retry.exception")))
                .isEqualTo("StaleRecordException");

        // AND - retry count attribute
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(1L);
    }

    @Test
    void no_retry_records_zero_count() throws Exception {
        // GIVEN
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
                .build());

        // WHEN
        executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(0L);

        // AND - no retry events
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
                .withAction(FailingItemAction.class, new FailingItemAction(clock, attempts))
                .build());

        // WHEN
        assertThatThrownBy(() ->
                executor.execute(() -> "user", FailingItemAction.class, new FailingItemAction.Params(10), config));

        // THEN
        var actionSpan = findSpan("ekbatan.action.execute");
        assertThat(actionSpan.getAttributes().get(AttributeKey.longKey("ekbatan.action.retry.count")))
                .isEqualTo(2L);

        // AND - 2 retry events
        var retryEvents = actionSpan.getEvents().stream()
                .filter(e -> e.getName().equals("retry"))
                .toList();
        assertThat(retryEvents).hasSize(2);
    }

    @Test
    void no_otel_sdk_does_not_affect_execution() throws Exception {
        // GIVEN / WHEN / THEN - this test class uses OpenTelemetryExtension which registers an SDK,
        // but the test validates that tracing doesn't break the action result
        var executor = buildExecutor(actionRegistry()
                .withAction(CreateItemAction.class, new CreateItemAction(clock))
                .build());

        var result = executor.execute(() -> "user", CreateItemAction.class, new CreateItemAction.Params("wallet"));
        assertThat(result.name).isEqualTo("wallet");
    }

    @Test
    void partial_cross_shard_failure_is_recorded_on_persist_span() throws Exception {
        // GIVEN - an action staging one item per shard, with cross-shard explicitly allowed
        var config = ExecutionConfiguration.Builder.executionConfiguration()
                .allowCrossShard(true)
                .build();
        var executor = buildTwoShardExecutor(actionRegistry()
                .withAction(CreateOnBothShardsAction.class, new CreateOnBothShardsAction(clock))
                .build());

        // WHEN - shard A commits, then shard B fails
        assertThatThrownBy(() -> executor.execute(
                        () -> "user", CreateOnBothShardsAction.class, new CreateOnBothShardsAction.Params(), config))
                .isInstanceOf(IllegalStateException.class);

        // THEN - the persist span is flagged as a partial commit
        var persistSpan = findSpan("ekbatan.action.persist");
        assertThat(persistSpan.getAttributes().get(AttributeKey.booleanKey("ekbatan.shard.partial_commit_failure")))
                .isTrue();

        // AND - it names which shard committed and which one failed
        assertThat(persistSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.shard.committed_shards")))
                .contains(SHARD_A.toString())
                .doesNotContain(SHARD_B.toString());
        assertThat(persistSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.shard.failed_shard")))
                .isEqualTo(SHARD_B.toString());

        // AND - the cross-shard flag and error status are set
        assertThat(persistSpan.getAttributes().get(AttributeKey.booleanKey("ekbatan.shard.cross_shard")))
                .isTrue();
        assertThat(persistSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void failure_before_any_shard_commits_is_not_flagged_as_partial() throws Exception {
        // GIVEN - an action touching only the failing shard, so nothing commits first
        var executor = buildTwoShardExecutor(actionRegistry()
                .withAction(CreateOnShardBAction.class, new CreateOnShardBAction(clock))
                .build());

        // WHEN
        assertThatThrownBy(() ->
                        executor.execute(() -> "user", CreateOnShardBAction.class, new CreateOnShardBAction.Params()))
                .isInstanceOf(IllegalStateException.class);

        // THEN - an ordinary single-shard rollback, not a partial commit
        var persistSpan = findSpan("ekbatan.action.persist");
        assertThat(persistSpan.getAttributes().get(AttributeKey.booleanKey("ekbatan.shard.partial_commit_failure")))
                .isNull();
        assertThat(persistSpan.getAttributes().get(AttributeKey.stringKey("ekbatan.shard.failed_shard")))
                .isNull();

        // AND - the span still records the failure
        assertThat(persistSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
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
            return plan().add(Item.createItem(params.name, clock.instant()).build());
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
            return plan().add(Item.createItem("recovered", clock.instant()).build());
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

    static class CreateOnBothShardsAction extends Action<CreateOnBothShardsAction.Params, Void> {
        record Params() {}

        CreateOnBothShardsAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Void perform(Principal principal, Params params) {
            // Staged A first, then B. Additions keep insertion order all the way through
            // groupChangesByShard, so the executor commits shard A before reaching shard B.
            plan().add(Item.createItem("a-item", clock.instant()).build());
            plan().add(Item.createItem("b-item", clock.instant()).build());
            return null;
        }
    }

    static class CreateOnShardBAction extends Action<CreateOnShardBAction.Params, Void> {
        record Params() {}

        CreateOnShardBAction(java.time.Clock clock) {
            super(clock);
        }

        @Override
        protected Void perform(Principal principal, Params params) {
            plan().add(Item.createItem("b-item", clock.instant()).build());
            return null;
        }
    }

    // --- Cross-shard routing test doubles ---

    /** Routes items whose name starts with {@code b-} to {@link #SHARD_B}, everything else to {@link #SHARD_A}. */
    static final class NamePrefixShardingStrategy implements ShardingStrategy<UUID> {

        @Override
        public boolean usesShardAwareId() {
            return false;
        }

        @Override
        public Optional<ShardIdentifier> resolveShardIdentifierById(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<ShardIdentifier> resolveShardIdentifier(Persistable<?> persistable) {
            if (persistable instanceof Item item) {
                return Optional.of(item.name.startsWith("b-") ? SHARD_B : SHARD_A);
            }
            return Optional.empty();
        }
    }

    static final class ShardRoutingRepository extends RecordingRepository {
        @Override
        public ShardingStrategy<?> shardingStrategy() {
            return new NamePrefixShardingStrategy();
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
