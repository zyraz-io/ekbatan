package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.TransferStep;
import io.example.wallet.model.TransferStepName;
import io.example.wallet.model.Wallet;
import io.example.wallet.repository.TransferStepRepository;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;

/**
 * Saga compensation - credits the source wallet back after a failed transfer. Emits a
 * {@code TransferRefundedEvent} that closes the saga loop and leaves a complete audit trail
 * on the source wallet:
 *
 * <pre>
 *   TransferInitiated -> TransferFailed -> TransferRefunded
 * </pre>
 *
 * <p>This is forward-only compensation, not a rollback. The original debit transaction is
 * already long committed by the time this runs - we simply add money back. The transfer-step
 * marker makes a replayed failure event a no-op instead of refunding twice.
 */
@EkbatanAction
public class RefundTransferAction extends Action<RefundTransferAction.Params, Wallet> {

    public record Params(UUID transferId, Id<Wallet> fromWalletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;
    private final TransferStepRepository transferStepRepository;

    public RefundTransferAction(
            Clock clock, WalletRepository walletRepository, TransferStepRepository transferStepRepository) {
        super(clock);
        this.walletRepository = walletRepository;
        this.transferStepRepository = transferStepRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        if (transferStepRepository.existsStep(params.transferId(), TransferStepName.REFUND_TRANSFER)) {
            return walletRepository.getById(params.fromWalletId().getValue());
        }

        plan().add(TransferStep.create(params.transferId(), TransferStepName.REFUND_TRANSFER)
                .build());

        final var source = walletRepository.getById(params.fromWalletId().getValue());
        final var refunded = source.refundTransfer(params.transferId(), params.amount());
        plan().update(refunded);
        return refunded;
    }
}
