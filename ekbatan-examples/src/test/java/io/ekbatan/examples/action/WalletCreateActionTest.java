package io.ekbatan.examples.action;

import static io.ekbatan.core.action.ActionRegistry.Builder.actionRegistry;
import static io.ekbatan.core.repository.RepositoryRegistry.Builder.repositoryRegistry;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.repository.ActionEventEntityRepository;
import io.ekbatan.core.repository.ModelEventEntityRepository;
import io.ekbatan.examples.test.PgBaseRepositoryTest;
import io.ekbatan.examples.wallet.action.WalletCreateAction;
import io.ekbatan.examples.wallet.action.WalletDepositMoneyAction;
import io.ekbatan.examples.wallet.models.Wallet;
import io.ekbatan.examples.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;

public class WalletCreateActionTest extends PgBaseRepositoryTest {

    @Test
    void test_action() throws Exception {
        final var walletRepository = new WalletRepository(transactionManager);
        final var actionEventEntityRepository = new ActionEventEntityRepository(transactionManager);
        final var modelEventEntityRepository = new ModelEventEntityRepository(transactionManager);

        final var repositoryRegistry = repositoryRegistry()
                .withModelRepository(Wallet.class, walletRepository)
                .withActionEventRepository(actionEventEntityRepository)
                .withModelEventRepository(modelEventEntityRepository)
                .build();

        final var actionRegistry = actionRegistry()
                .withAction(WalletCreateAction.class, new WalletCreateAction())
                .withAction(WalletDepositMoneyAction.class, new WalletDepositMoneyAction(walletRepository))
                .build();

        final var actionExecutor = new ActionExecutor(transactionManager, repositoryRegistry, actionRegistry);

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
