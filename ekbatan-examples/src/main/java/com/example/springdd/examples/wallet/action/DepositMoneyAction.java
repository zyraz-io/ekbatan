// package com.example.springdd.examples.wallet.action;
//
// import com.example.springdd.core.action.Action;
// import com.example.springdd.core.action.BaseAction;
// import com.example.springdd.examples.wallet.repository.WalletRepository;
// import java.math.BigDecimal;
// import java.util.Objects;
// import org.springframework.stereotype.Component;
//
/// **
// * Action to deposit money into a wallet.
// */
// @Component
// public class DepositMoneyAction extends BaseAction<String, DepositMoneyAction.Params, Wallet> {
//
//    private final WalletRepository walletRepository;
//
//    public DepositMoneyAction(WalletRepository walletRepository) {
//        this.walletRepository = walletRepository;
//    }
//
//    @Override
//    protected Wallet doExecute(String principal, Params params) throws Exception {
//        Objects.requireNonNull(params, "Params cannot be null");
//        params.validate();
//
//        // Find the wallet
//        Wallet wallet = walletRepository
//                .findById(params.walletId)
//                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + params.walletId));
//
//        // Deposit the money
//        Wallet updatedWallet = wallet.deposit(params.amount);
//
//        // Save the updated wallet
//        return walletRepository.save(updatedWallet);
//    }
//
//    /**
//     * Parameters for the deposit money action.
//     */
//    public static class Params implements Action.Params {
//        private final Wallet.Id walletId;
//        private final BigDecimal amount;
//
//        public Params(Wallet.Id walletId, BigDecimal amount) {
//            this.walletId = walletId;
//            this.amount = amount;
//        }
//
//        @Override
//        public void validate() {
//            if (walletId == null) {
//                throw new IllegalArgumentException("Wallet ID cannot be null");
//            }
//            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
//                throw new IllegalArgumentException("Amount must be positive");
//            }
//        }
//    }
// }
