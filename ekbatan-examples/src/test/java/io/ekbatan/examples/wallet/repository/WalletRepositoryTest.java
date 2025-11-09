package io.ekbatan.examples.wallet.repository;

import static io.ekbatan.examples.wallet.models.Wallet.createWallet;
import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.examples.test.BaseRepositoryTest;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Currency;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WalletRepositoryTest extends BaseRepositoryTest {

    private WalletRepository walletRepository;
    private DSLContext dslContext;

    @BeforeEach
    void setUp() throws SQLException {
        // Initialize jOOQ DSLContext
        dslContext = DSL.using(connection, SQLDialect.POSTGRES);

        // Initialize repository with the DSLContext
        walletRepository = new WalletRepository(dslContext);
    }

    @Test
    void shouldSaveAndRetrieveWallet() {
        // Given
        final var wallet = createWallet(UUID.randomUUID().toString(), Currency.getInstance("EUR"), BigDecimal.TEN)
                .build();

        // When
        walletRepository.add(wallet);
        final var found = walletRepository.findById(wallet.getId().getValue());

        // Then
        assertThat(found).isPresent();
        assertThat(found.orElseThrow().id).isEqualTo(wallet.id);
        assertThat(found.orElseThrow().state).isEqualTo(wallet.state);
        assertThat(found.orElseThrow().ownerId).isEqualTo(wallet.ownerId);
        assertThat(found.orElseThrow().currency.getCurrencyCode()).isEqualTo("EUR");
        assertThat(found.orElseThrow().balance.intValue()).isEqualTo(10);
        assertThat(found.orElseThrow().createdDate).isEqualTo(wallet.createdDate);
        assertThat(found.orElseThrow().updatedDate).isEqualTo(wallet.updatedDate);
    }
}
