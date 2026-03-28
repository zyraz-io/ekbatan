package io.ekbatan.test.postgres_dual_table_events.wallet.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static io.ekbatan.core.shard.DatabaseRegistry.Builder.databaseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.persister.event.dual_table.DualTableEventPersister;
import io.ekbatan.core.shard.ShardIdentifier;
import io.ekbatan.test.postgres_dual_table_events.test.PgRepositoryTest;
import io.ekbatan.test.postgres_dual_table_events.wallet.models.Wallet;
import io.ekbatan.test.postgres_dual_table_events.wallet.repository.WalletRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class WalletActionIntegrationTest extends PgRepositoryTest {

    @Test
    void test_action() throws Exception {
        // GIVEN
        final var objectMapper = new ObjectMapper();

        var databaseRegistry = databaseRegistry()
                .withDatabase(ShardIdentifier.DEFAULT, transactionManager)
                .defaultShard(ShardIdentifier.DEFAULT)
                .build();

        final var walletRepository = new WalletRepository(databaseRegistry);

        final var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepository)
                .build();

        final var clock = Clock.systemUTC();

        final var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, () -> new WalletCreateAction(clock))
                .withAction(WalletDepositMoneyAction.class, () -> new WalletDepositMoneyAction(clock, walletRepository))
                .build();

        final var eventPersister = new DualTableEventPersister(databaseRegistry, objectMapper);

        final var actionExecutor = actionExecutor()
                .databaseRegistry(databaseRegistry)
                .objectMapper(objectMapper)
                .repositoryRegistry(repositoryRegistry)
                .actionRegistry(actionRegistry)
                .eventPersister(eventPersister)
                .build();

        // WHEN
        final var result =
                actionExecutor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params());

        // THEN
        final var fetchedWallet = walletRepository.getById(result.getId().getValue());
        assertThat(result).isEqualTo(fetchedWallet);

        // WHEN
        final var result2 = actionExecutor.execute(
                () -> "test-user", WalletDepositMoneyAction.class, new WalletDepositMoneyAction.Params(result.getId()));

        // THEN
        final var fetchedWallet2 = walletRepository.getById(result2.getId().getId());
        assertThat(result2).isEqualTo(fetchedWallet2);
    }
}
