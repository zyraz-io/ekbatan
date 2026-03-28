package io.ekbatan.test.postgres_sharded.wallet.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.action.ExecutionConfiguration.Builder.executionConfiguration;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.shard.CrossShardException;
import io.ekbatan.core.shard.DatabaseRegistry;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.postgres_sharded.test.PgShardedBaseTest;
import io.ekbatan.test.postgres_sharded.wallet.models.Wallet;
import io.ekbatan.test.postgres_sharded.wallet.repository.WalletRepository;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class ShardedWalletIntegrationTest extends PgShardedBaseTest {

    private WalletRepository walletRepo;
    private ActionExecutor executor;

    @BeforeEach
    void setUp() {
        walletRepo = new WalletRepository(databaseRegistry);

        var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepo)
                .build();

        var clock = Clock.systemUTC();

        var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, () -> new WalletCreateAction(clock))
                .withAction(WalletCreateMultiShardAction.class, () -> new WalletCreateMultiShardAction(clock))
                .build();

        executor = actionExecutor()
                .databaseRegistry(databaseRegistry)
                .objectMapper(new ObjectMapper())
                .repositoryRegistry(repositoryRegistry)
                .actionRegistry(actionRegistry)
                .build();
    }

    // --- Single-shard tests ---

    @Test
    void single_shard_action_persists_wallet_to_correct_shard() throws Exception {
        // WHEN
        var wallet = executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("DE"));

        // THEN — wallet on global shard
        var globalOnlyRepo = new WalletRepository(singleShardRegistry(GLOBAL_SHARD));
        assertThat(globalOnlyRepo.findById(wallet.id.getValue())).isPresent();

        // AND — not on mexico shard
        var mexicoOnlyRepo = new WalletRepository(singleShardRegistry(MEXICO_SHARD));
        assertThat(mexicoOnlyRepo.findById(wallet.id.getValue())).isEmpty();
    }

    @Test
    void wallets_are_isolated_between_shards() throws Exception {
        // WHEN
        var globalWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("DE"));
        var mexicoWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("MX"));

        // THEN — sharded repo finds both
        assertThat(walletRepo.findById(globalWallet.id.getValue())).isPresent();
        assertThat(walletRepo.findById(mexicoWallet.id.getValue())).isPresent();

        // AND — per-shard isolation
        var globalOnlyRepo = new WalletRepository(singleShardRegistry(GLOBAL_SHARD));
        var mexicoOnlyRepo = new WalletRepository(singleShardRegistry(MEXICO_SHARD));

        assertThat(globalOnlyRepo.findById(globalWallet.id.getValue())).isPresent();
        assertThat(globalOnlyRepo.findById(mexicoWallet.id.getValue())).isEmpty();

        assertThat(mexicoOnlyRepo.findById(mexicoWallet.id.getValue())).isPresent();
        assertThat(mexicoOnlyRepo.findById(globalWallet.id.getValue())).isEmpty();
    }

    @Test
    void single_shard_action_events_on_correct_shard() throws Exception {
        // GIVEN
        var beforeGlobal = countActionEvents(GLOBAL_SHARD);
        var beforeMexico = countActionEvents(MEXICO_SHARD);

        // WHEN
        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("MX"));

        // THEN — one new action_events on mexico shard
        assertThat(countActionEvents(MEXICO_SHARD)).isEqualTo(beforeMexico + 1);

        // AND — no new action_events on global shard
        assertThat(countActionEvents(GLOBAL_SHARD)).isEqualTo(beforeGlobal);
    }

    // --- Cross-shard enforcement tests ---

    @Test
    void cross_shard_action_throws_by_default() {
        // WHEN / THEN — default config disallows cross-shard
        assertThatThrownBy(() -> executor.execute(
                        () -> "test-user",
                        WalletCreateMultiShardAction.class,
                        new WalletCreateMultiShardAction.Params()))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void cross_shard_action_throws_with_explicit_disallow() {
        // GIVEN
        var config = executionConfiguration().allowCrossShard(false).build();

        // WHEN / THEN
        assertThatThrownBy(() -> executor.execute(
                        () -> "test-user",
                        WalletCreateMultiShardAction.class,
                        new WalletCreateMultiShardAction.Params(),
                        config))
                .isInstanceOf(CrossShardException.class);
    }

    @Test
    void cross_shard_action_no_data_persisted_when_rejected() {
        // GIVEN
        var beforeGlobal = countWallets(GLOBAL_SHARD);
        var beforeMexico = countWallets(MEXICO_SHARD);

        // WHEN
        try {
            executor.execute(
                    () -> "test-user", WalletCreateMultiShardAction.class, new WalletCreateMultiShardAction.Params());
        } catch (Exception _) {
        }

        // THEN — no new wallets on either shard
        assertThat(countWallets(GLOBAL_SHARD)).isEqualTo(beforeGlobal);
        assertThat(countWallets(MEXICO_SHARD)).isEqualTo(beforeMexico);
    }

    // --- Cross-shard allowed tests ---

    @Test
    void cross_shard_action_allowed_persists_data_and_events_correctly() throws Exception {
        // GIVEN
        var config = executionConfiguration().allowCrossShard(true).build();
        var beforeGlobalWallets = countWallets(GLOBAL_SHARD);
        var beforeMexicoWallets = countWallets(MEXICO_SHARD);
        var beforeGlobalEvents = countActionEvents(GLOBAL_SHARD);
        var beforeMexicoEvents = countActionEvents(MEXICO_SHARD);

        // WHEN
        executor.execute(
                () -> "test-user",
                WalletCreateMultiShardAction.class,
                new WalletCreateMultiShardAction.Params(),
                config);

        // THEN — one new wallet on each shard
        assertThat(countWallets(GLOBAL_SHARD)).isEqualTo(beforeGlobalWallets + 1);
        assertThat(countWallets(MEXICO_SHARD)).isEqualTo(beforeMexicoWallets + 1);

        // AND — EUR wallet on global, MXN wallet on mexico
        var globalWallets = new WalletRepository(singleShardRegistry(GLOBAL_SHARD)).findAll();
        var mexicoWallets = new WalletRepository(singleShardRegistry(MEXICO_SHARD)).findAll();
        assertThat(globalWallets).anyMatch(w -> w.currency.getCurrencyCode().equals("EUR"));
        assertThat(mexicoWallets).anyMatch(w -> w.currency.getCurrencyCode().equals("MXN"));

        // AND — action_events duplicated to both shards
        assertThat(countActionEvents(GLOBAL_SHARD)).isEqualTo(beforeGlobalEvents + 1);
        assertThat(countActionEvents(MEXICO_SHARD)).isEqualTo(beforeMexicoEvents + 1);

        // AND — the duplicated action_events share the same UUID
        var globalIds = fetchActionEventIds(GLOBAL_SHARD);
        var mexicoIds = fetchActionEventIds(MEXICO_SHARD);
        assertThat(globalIds).containsAnyElementsOf(mexicoIds);
    }

    // --- Effective shard fallback tests (unregistered shard → default) ---

    @Test
    void unregistered_shard_wallet_persisted_to_default_shard() throws Exception {
        // GIVEN
        var beforeGlobal = countWallets(GLOBAL_SHARD);
        var beforeMexico = countWallets(MEXICO_SHARD);

        // WHEN — AU maps to shard (2,0) which is not registered → falls back to global
        var wallet = executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("AU"));

        // THEN — wallet on global shard
        assertThat(countWallets(GLOBAL_SHARD)).isEqualTo(beforeGlobal + 1);

        // AND — not on mexico shard
        assertThat(countWallets(MEXICO_SHARD)).isEqualTo(beforeMexico);

        // AND — findable via sharded repo
        assertThat(walletRepo.findById(wallet.id.getValue())).isPresent();
    }

    @Test
    void unregistered_shard_wallet_findable_alongside_registered_shards() throws Exception {
        // GIVEN
        var globalWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("DE"));
        var mexicoWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("MX"));
        var australiaWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("AU"));

        // THEN — all three findable via sharded repo
        assertThat(walletRepo.findById(globalWallet.id.getValue())).isPresent();
        assertThat(walletRepo.findById(mexicoWallet.id.getValue())).isPresent();
        assertThat(walletRepo.findById(australiaWallet.id.getValue())).isPresent();
    }

    @Test
    void unregistered_shard_wallet_action_events_on_default_shard() throws Exception {
        // GIVEN
        var beforeGlobal = countActionEvents(GLOBAL_SHARD);
        var beforeMexico = countActionEvents(MEXICO_SHARD);

        // WHEN
        executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("AU"));

        // THEN — action event on global (default) shard
        assertThat(countActionEvents(GLOBAL_SHARD)).isEqualTo(beforeGlobal + 1);

        // AND — no new action event on mexico shard
        assertThat(countActionEvents(MEXICO_SHARD)).isEqualTo(beforeMexico);
    }

    @Test
    void unregistered_shard_wallets_coexist_with_default_shard_wallets() throws Exception {
        // GIVEN — DE wallet (global shard) and AU wallet (unregistered → global shard)
        var deWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("DE"));
        var auWallet =
                executor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params("AU"));

        // THEN — both physically on global shard
        var globalOnlyRepo = new WalletRepository(singleShardRegistry(GLOBAL_SHARD));
        assertThat(globalOnlyRepo.findById(deWallet.id.getValue())).isPresent();
        assertThat(globalOnlyRepo.findById(auWallet.id.getValue())).isPresent();

        // AND — neither on mexico shard
        var mexicoOnlyRepo = new WalletRepository(singleShardRegistry(MEXICO_SHARD));
        assertThat(mexicoOnlyRepo.findById(deWallet.id.getValue())).isEmpty();
        assertThat(mexicoOnlyRepo.findById(auWallet.id.getValue())).isEmpty();
    }

    // --- Helpers ---

    private DatabaseRegistry singleShardRegistry(ShardIdentifier shard) {
        return databaseRegistry()
                .withDatabase(shard, databaseRegistry.transactionManager(shard))
                .defaultShard(shard)
                .build();
    }

    private long countWallets(ShardIdentifier shard) {
        return databaseRegistry.primary.get(shard).selectCount().from("wallets").fetchOne(0, long.class);
    }

    private java.util.List<java.util.UUID> fetchActionEventIds(ShardIdentifier shard) {
        return databaseRegistry
                .primary
                .get(shard)
                .select(org.jooq.impl.DSL.field("id", java.util.UUID.class))
                .from("eventlog.action_events")
                .fetch(0, java.util.UUID.class);
    }

    private long countActionEvents(ShardIdentifier shard) {
        return databaseRegistry
                .primary
                .get(shard)
                .selectCount()
                .from("eventlog.action_events")
                .fetchOne(0, long.class);
    }
}
