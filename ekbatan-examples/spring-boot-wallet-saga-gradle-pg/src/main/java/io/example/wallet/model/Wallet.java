package io.example.wallet.model;

import static io.example.wallet.model.WalletState.CLOSED;
import static io.example.wallet.model.WalletState.OPENED;

import io.ekbatan.core.domain.Id;
import io.ekbatan.core.domain.Model;
import io.ekbatan.core.processor.AutoBuilder;
import io.example.wallet.model.events.TransferCompletedEvent;
import io.example.wallet.model.events.TransferFailedEvent;
import io.example.wallet.model.events.TransferInitiatedEvent;
import io.example.wallet.model.events.TransferRefundedEvent;
import io.example.wallet.model.events.WalletClosedEvent;
import io.example.wallet.model.events.WalletCreatedEvent;
import io.example.wallet.model.events.WalletMoneyDepositedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.apache.commons.lang3.Validate;

@AutoBuilder
public final class Wallet extends Model<Wallet, Id<Wallet>, WalletState> {

    public final UUID ownerId;
    public final Currency currency;
    public final BigDecimal balance;

    Wallet(WalletBuilder builder) {
        super(builder);
        this.ownerId = Validate.notNull(builder.ownerId, "ownerId cannot be null");
        this.currency = Validate.notNull(builder.currency, "currency cannot be null");
        this.balance = Validate.notNull(builder.balance, "balance cannot be null");
    }

    public static WalletBuilder createWallet(
            UUID ownerId, Currency currency, BigDecimal initialBalance, Instant createdDate) {
        final var id = Id.random(Wallet.class);
        return WalletBuilder.wallet()
                .id(id)
                .state(OPENED)
                .ownerId(ownerId)
                .currency(currency)
                .balance(initialBalance)
                .createdDate(createdDate)
                .withInitialVersion()
                .withEvent(new WalletCreatedEvent(id, ownerId, currency, initialBalance));
    }

    @Override
    public WalletBuilder copy() {
        return WalletBuilder.wallet()
                .copyBase(this)
                .ownerId(ownerId)
                .currency(currency)
                .balance(balance);
    }

    public Wallet deposit(BigDecimal amount) {
        Validate.notNull(amount, "amount cannot be null");
        Validate.isTrue(amount.compareTo(BigDecimal.ZERO) > 0, "Deposit amount must be positive");
        final var newBalance = balance.add(amount);
        return copy().withEvent(new WalletMoneyDepositedEvent(id, amount, newBalance))
                .balance(newBalance)
                .build();
    }

    public Wallet close() {
        if (state.equals(CLOSED)) {
            return this;
        }
        return copy().withEvent(new WalletClosedEvent(id)).state(CLOSED).build();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Saga operations — each maps cleanly to one step of the transfer saga.
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Saga step 1 — debit the source wallet and attach a {@link TransferInitiatedEvent}. The
     * action that calls this still has to {@code plan().update(this)} for the change to
     * commit; this method just produces the new wallet state + event in one place.
     */
    public Wallet initiateTransferOut(UUID transferId, Id<Wallet> toWalletId, BigDecimal amount) {
        Validate.isTrue(state.equals(OPENED), "Source wallet must be OPEN to initiate a transfer");
        Validate.isTrue(balance.compareTo(amount) >= 0, "Insufficient balance for transfer");
        final var newBalance = balance.subtract(amount);
        return copy().withEvent(new TransferInitiatedEvent(
                        id, transferId, id.getValue(), toWalletId.getValue(), amount, newBalance))
                .balance(newBalance)
                .build();
    }

    /** Saga step 2 (happy path) — credit the destination wallet and attach a {@link TransferCompletedEvent}. */
    public Wallet completeTransferIn(UUID transferId, Id<Wallet> fromWalletId, BigDecimal amount) {
        Validate.isTrue(state.equals(OPENED), "Destination wallet must be OPEN to receive a transfer");
        final var newBalance = balance.add(amount);
        return copy().withEvent(new TransferCompletedEvent(
                        id, transferId, fromWalletId.getValue(), id.getValue(), amount, newBalance))
                .balance(newBalance)
                .build();
    }

    /**
     * Saga step 2 (failure path) — record on the <em>source</em> wallet that the transfer can't
     * complete. No balance change here; only the event is attached. The framework's update will
     * still bump version + {@code updated_date}, so the row writes and the event lands in the
     * outbox where the failure handler picks it up.
     */
    public Wallet markTransferFailed(UUID transferId, Id<Wallet> toWalletId, BigDecimal amount, String reason) {
        return copy().withEvent(
                        new TransferFailedEvent(id, transferId, id.getValue(), toWalletId.getValue(), amount, reason))
                .build();
    }

    /** Saga compensation — credit the source wallet back and attach a {@link TransferRefundedEvent}. */
    public Wallet refundTransfer(UUID transferId, BigDecimal amount) {
        final var newBalance = balance.add(amount);
        return copy().withEvent(new TransferRefundedEvent(id, transferId, id.getValue(), amount, newBalance))
                .balance(newBalance)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Wallet wallet = (Wallet) o;
        return ownerId.equals(wallet.ownerId)
                && currency.equals(wallet.currency)
                && balance.compareTo(wallet.balance) == 0;
    }
}
