package io.example.wallet.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.core.concurrent.KeyedLockProvider;
import io.ekbatan.di.EkbatanDistributedJob;
import io.ekbatan.distributedjobs.DistributedJob;
import io.example.wallet.action.WalletDepositMoneyAction;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically tops up under-funded wallets - the headline "job invokes action" pattern.
 *
 * <p>Every {@link #schedule() schedule slot}, the job:
 *
 * <ol>
 *   <li>Reads open wallets with {@code balance < THRESHOLD} via the read-only repository
 *       (no transaction owned by the job - actions own their own transactions).</li>
 *   <li>For each such wallet, invokes {@link WalletDepositMoneyAction} through
 *       {@link ActionExecutor}. The action opens its own transaction, applies the deposit,
 *       emits a {@code WalletMoneyDepositedEvent} to the outbox, and commits.</li>
 *   <li>The framework's local-event-handler picks up each event asynchronously and triggers
 *       {@code WalletMoneyDepositedEventHandler} -> {@code CreateNotificationAction} -> a row
 *       in {@code notifications} - same listen-to-yourself chain the REST examples exercise,
 *       only here it's job-driven instead of request-driven.</li>
 * </ol>
 *
 * <p>Failures during a single wallet's deposit are caught and logged - the job continues with
 * the next wallet so one bad row doesn't halt the whole pass. db-scheduler would otherwise
 * count an uncaught exception as a failed execution and increment
 * {@code scheduled_tasks.consecutive_failures}; this job intentionally chooses per-wallet
 * resilience over fail-fast semantics.
 */
@EkbatanDistributedJob
public class WalletStipendJob extends DistributedJob {

    private static final Logger LOG = LoggerFactory.getLogger(WalletStipendJob.class);

    /** Wallets below this balance get a top-up on each scheduled run. */
    private static final BigDecimal THRESHOLD = new BigDecimal("100.00");

    /** Amount deposited per wallet per scheduled run. */
    private static final BigDecimal STIPEND = new BigDecimal("10.00");

    private final ActionExecutor executor;
    private final KeyedLockProvider lockProvider;
    private final WalletRepository walletRepository;

    public WalletStipendJob(
            ActionExecutor executor, KeyedLockProvider lockProvider, WalletRepository walletRepository) {
        this.executor = executor;
        this.lockProvider = lockProvider;
        this.walletRepository = walletRepository;
    }

    @Override
    public String name() {
        // Must be cluster-wide unique. db-scheduler persists this in scheduled_tasks.task_name
        // and uses it to coordinate at-most-once-per-slot across worker instances.
        return "wallet-stipend";
    }

    @Override
    public Schedule schedule() {
        return FixedDelay.ofSeconds(2);
    }

    @Override
    public void execute(ExecutionContext ctx) {
        final var underFunded = walletRepository.findOpenWithBalanceBelow(THRESHOLD);
        if (underFunded.isEmpty()) {
            return;
        }
        LOG.info("WalletStipendJob: depositing {} to {} wallet(s)", STIPEND, underFunded.size());
        for (var wallet : underFunded) {
            try (var lease = lockProvider.acquire("wallet:" + wallet.id.getValue(), Duration.ofSeconds(10))) {
                executor.execute(
                        () -> "wallet-stipend-job",
                        WalletDepositMoneyAction.class,
                        new WalletDepositMoneyAction.Params(wallet.id, STIPEND, "stipend@system.local"));
            } catch (Exception e) {
                LOG.warn("WalletStipendJob: deposit failed for wallet {} - skipping", wallet.id.getValue(), e);
            }
        }
    }
}
