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
 * Saga compensation — credits the source wallet back after a failed transfer. Emits a
 * {@code TransferRefundedEvent} that closes the saga loop and leaves a complete audit trail
 * on the source wallet:
 *
 * <pre>
 *   TransferInitiated → TransferFailed → TransferRefunded
 * </pre>
 *
 * <p>This is forward-only compensation, not a rollback. The original debit transaction is
 * already long committed by the time this runs — we simply add money back. The framework's
 * outbox guarantees this compensation runs exactly once per failure event.
 */
@EkbatanAction
public class RefundTransferAction extends Action<RefundTransferAction.Params, Wallet> {

    public record Params(UUID transferId, Id<Wallet> fromWalletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public RefundTransferAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var source = walletRepository.getById(params.fromWalletId().getValue());
        final var refunded = source.refundTransfer(params.transferId(), params.amount());
        plan().update(refunded);
        return refunded;
    }
}
