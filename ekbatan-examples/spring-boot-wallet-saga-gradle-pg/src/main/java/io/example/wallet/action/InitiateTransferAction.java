package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;

/**
 * Saga step 1 — debits the source wallet and emits {@code TransferInitiatedEvent}.
 *
 * <p>The transfer's UUID is generated here; it correlates every later event in the saga
 * (completion, failure, refund). The action commits in one transaction: source wallet's row
 * is updated and the event lands in {@code eventlog.events} atomically. The framework's
 * in-process fan-out then drives step 2 via {@code TransferInitiatedEventHandler}.
 */
@EkbatanAction
public class InitiateTransferAction extends Action<InitiateTransferAction.Params, InitiateTransferAction.Result> {

    public record Params(Id<Wallet> fromWalletId, Id<Wallet> toWalletId, BigDecimal amount) {}

    public record Result(UUID transferId, Wallet sourceWallet) {}

    private final WalletRepository walletRepository;

    public InitiateTransferAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Result perform(Principal principal, Params params) {
        final var transferId = UUID.randomUUID();
        final var source = walletRepository.getById(params.fromWalletId().getValue());
        final var debited = source.initiateTransferOut(transferId, params.toWalletId(), params.amount());
        plan().update(debited);
        return new Result(transferId, debited);
    }
}
