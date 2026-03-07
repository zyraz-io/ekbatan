package io.ekbatan.examples.postgres_dual_table_events.action;

import static io.ekbatan.core.action.ActionExecutor.Builder.actionExecutor;
import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.persister.event.dual_table.DualTableEventPersister;
import io.ekbatan.examples.postgres_dual_table_events.test.PgBaseRepositoryTest;
import io.ekbatan.examples.postgres_dual_table_events.wallet.action.WalletCreateAction;
import io.ekbatan.examples.postgres_dual_table_events.wallet.action.WalletDepositMoneyAction;
import io.ekbatan.examples.postgres_dual_table_events.wallet.models.Wallet;
import io.ekbatan.examples.postgres_dual_table_events.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class WalletCreateActionTest extends PgBaseRepositoryTest {

    @Test
    void test_action() throws Exception {
        final var objectMapper = new ObjectMapper();

        final var walletRepository = new WalletRepository(transactionManager);

        final var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepository)
                .build();

        final var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, WalletCreateAction::new)
                .withAction(WalletDepositMoneyAction.class, () -> new WalletDepositMoneyAction(walletRepository))
                .build();

        final var eventPersister = new DualTableEventPersister(transactionManager, objectMapper);

        final var actionExecutor = actionExecutor()
                .transactionManager(transactionManager)
                .objectMapper(objectMapper)
                .repositoryRegistry(repositoryRegistry)
                .actionRegistry(actionRegistry)
                .eventPersister(eventPersister)
                .build();

        final var result =
                actionExecutor.execute(() -> "test-user", WalletCreateAction.class, new WalletCreateAction.Params());

        final var fetchedWallet = walletRepository.getById(result.getId().getValue());
        assertThat(result).isEqualTo(fetchedWallet);

        final var result2 = actionExecutor.execute(
                () -> "test-user", WalletDepositMoneyAction.class, new WalletDepositMoneyAction.Params(result.getId()));

        final var fetchedWallet2 = walletRepository.getById(result2.getId().getId());

        assertThat(result2).isEqualTo(fetchedWallet2);
        //        assertThat(result2).usingRecursiveComparison().ignoringFields("version").isEqualTo(fetchedWallet2);

    }
}
