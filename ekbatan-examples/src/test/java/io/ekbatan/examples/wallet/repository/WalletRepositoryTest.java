package io.ekbatan.examples.wallet.repository;

import static io.ekbatan.examples.wallet.models.Wallet.createWallet;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.examples.test.BaseRepositoryTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Currency;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WalletRepositoryTest extends BaseRepositoryTest {

    private WalletRepository walletRepository;

    @BeforeEach
    void setUp() throws SQLException {
        walletRepository = new WalletRepository(transactionManager);
    }

    @Test
    void shouldSaveAndRetrieveWallet() {
        // GIVEN
        final var wallet = createWallet(UUID.randomUUID().toString(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        // WHEN
        walletRepository.add(wallet);
        final var found = walletRepository.findById(wallet.getId().getValue());

        // THEN
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(found.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(found.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(found.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(found.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(found.orElseThrow().createdDate).isEqualTo(wallet.createdDate);
        assertThat(found.orElseThrow().updatedDate).isEqualTo(wallet.updatedDate);
    }

    @Test
    void testInTransaction_should_commit() {
        // GIVEN / WHEN

        final var wallet = createWallet(UUID.randomUUID().toString(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        transactionManager.inTransaction(dslContext -> {
            walletRepository.add(wallet);
        });

        // THEN

        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isPresent();
        assertThat(fetchedWallet.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(fetchedWallet.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(fetchedWallet.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(fetchedWallet.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(fetchedWallet.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(fetchedWallet.orElseThrow().createdDate).isEqualTo(wallet.createdDate);
        assertThat(fetchedWallet.orElseThrow().updatedDate).isEqualTo(wallet.updatedDate);
    }

    @Test
    void testInTransaction_should_rollback() {
        // GIVEN / WHEN

        final var wallet = createWallet(UUID.randomUUID().toString(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        try {
            transactionManager.inTransaction((Consumer<DSLContext>) dslContext -> {
                walletRepository.add(wallet);

                throw new RuntimeException();
            });
        } catch (Exception _) {

        }

        // THEN

        final var fetchedWallet = walletRepository.findById(wallet.getId().getValue());
        assertThat(fetchedWallet).isEmpty();
    }
}
