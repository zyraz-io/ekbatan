package io.example.wallet.action;

import io.ekbatan.core.action.Action;
import io.ekbatan.core.domain.Id;
import io.ekbatan.di.EkbatanAction;
import io.example.wallet.model.Wallet;
import io.example.wallet.model.WalletState;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.util.UUID;

/**
 * Saga step 2 - tries to credit the destination wallet. Emits one of two events:
 *
 * <ul>
 *   <li>{@code TransferCompletedEvent} on the destination wallet on success.</li>
 *   <li>{@code TransferFailedEvent} on the <em>source</em> wallet when the destination is
 *       missing, closed, or otherwise unable to receive - which triggers the compensation
 *       (refund) chain via {@code TransferFailedEventHandler}.</li>
 * </ul>
 *
 * <p>The currency mismatch case is handled as a failure here. In a real app you'd likely call
 * out to an FX service and either route through an intermediate wallet or compute conversion
 * inline; either way, the saga's structure stays the same.
 */
@EkbatanAction
public class CompleteTransferAction extends Action<CompleteTransferAction.Params, Wallet> {

    public record Params(UUID transferId, Id<Wallet> fromWalletId, Id<Wallet> toWalletId, BigDecimal amount) {}

    private final WalletRepository walletRepository;

    public CompleteTransferAction(Clock clock, WalletRepository walletRepository) {
        super(clock);
        this.walletRepository = walletRepository;
    }

    @Override
    protected Wallet perform(Principal principal, Params params) {
        final var maybeDestination =
                walletRepository.findById(params.toWalletId().getValue());

        // Failure path A: destination doesn't exist
        if (maybeDestination.isEmpty()) {
            return failOnSource(params, "destination wallet not found");
        }

        final var destination = maybeDestination.get();

        // Failure path B: destination is closed
        if (destination.state != WalletState.OPENED) {
            return failOnSource(params, "destination wallet is " + destination.state.name());
        }

        // Failure path C: currency mismatch
        final var source = walletRepository.getById(params.fromWalletId().getValue());
        if (!destination.currency.equals(source.currency)) {
            return failOnSource(
                    params,
                    "currency mismatch: source=" + source.currency.getCurrencyCode() + " destination="
                            + destination.currency.getCurrencyCode());
        }

        // Happy path - credit destination.
        final var credited =
                destination.completeTransferIn(params.transferId(), params.fromWalletId(), params.amount());
        plan().update(credited);
        return credited;
    }

    private Wallet failOnSource(Params params, String reason) {
        final var source = walletRepository.getById(params.fromWalletId().getValue());
        final var marked = source.markTransferFailed(params.transferId(), params.toWalletId(), params.amount(), reason);
        plan().update(marked);
        return marked;
    }
}
