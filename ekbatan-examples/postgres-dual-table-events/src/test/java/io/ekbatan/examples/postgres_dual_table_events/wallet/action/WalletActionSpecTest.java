package io.ekbatan.examples.postgres_dual_table_events.wallet.action;

import static org.assertj.core.api.Assertions.assertThat;

import io.ekbatan.core.action.ActionSpec;
import io.ekbatan.core.time.VirtualClock;
import io.ekbatan.examples.postgres_dual_table_events.wallet.models.Wallet;
import io.ekbatan.examples.postgres_dual_table_events.wallet.models.WalletState;
import io.ekbatan.examples.postgres_dual_table_events.wallet.models.events.WalletCreatedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class WalletActionSpecTest {

    @Test
    void create_wallet() throws Exception {
        // GIVEN
        var clock = new VirtualClock();
        clock.pauseAt(Instant.parse("2025-01-01T00:00:00Z"));

        // WHEN / THEN
        ActionSpec.of(new WalletCreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new WalletCreateAction.Params())
                .assertAdded(Wallet.class, wallet -> {
                    assertThat(wallet.state).isEqualTo(WalletState.OPENED);
                    assertThat(wallet.currency).isEqualTo(Currency.getInstance("EUR"));
                    assertThat(wallet.balance).isEqualByComparingTo(BigDecimal.TEN);
                    assertThat(wallet.createdDate).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
                })
                .assertEmitted(WalletCreatedEvent.class, event -> {
                    assertThat(event.currency).isEqualTo(Currency.getInstance("EUR"));
                    assertThat(event.initialBalance).isEqualByComparingTo(BigDecimal.TEN);
                })
                .assertNoUpdates();
    }

    @Test
    void create_wallet_result() throws Exception {
        // GIVEN
        var clock = new VirtualClock();

        // WHEN / THEN
        ActionSpec.of(new WalletCreateAction(clock))
                .withPrincipal(() -> "test-user")
                .execute(new WalletCreateAction.Params())
                .assertResult(wallet -> {
                    assertThat(wallet.version).isEqualTo(1L);
                    assertThat(wallet.id).isNotNull();
                });
    }
}
