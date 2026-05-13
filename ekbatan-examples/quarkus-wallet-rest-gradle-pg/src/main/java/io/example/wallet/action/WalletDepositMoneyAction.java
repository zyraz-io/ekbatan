package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;

/**
 * Updates the wallet's balance and emits a {@code WalletMoneyDepositedEvent}.
 *
 * <p>The notification side-effect lives in {@link io.example.wallet.handler.WalletMoneyDepositedEventHandler},
 * which receives the event in-process and creates a Notification via {@link CreateNotificationAction}.
 * That's the listen-to-yourself pattern — keeping notification concerns out of the deposit action
 * itself. The {@code recipient} is still part of {@link Params} so the handler can read it back
 * via {@code EventEnvelope.actionParams} without polluting the domain event payload.
 */
@EkbatanAction
public class WalletDepositMoneyAction extends Action<WalletDepositMoneyAction.Params, Wallet> {

    public record Params(Id<Wallet> walletId, BigDecimal amount, String recipient) {}

    private final WalletRepository walletRepository;

    public WalletDepositMoneyAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var wallet = walletRepository.getById(params.walletId().getValue());
        final var updated = wallet.deposit(params.amount());
        return plan().update(updated);
    }
}
