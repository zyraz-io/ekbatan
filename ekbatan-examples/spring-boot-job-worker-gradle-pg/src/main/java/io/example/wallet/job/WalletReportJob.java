package io.example.wallet.job;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.di.EkbatanDistributedJob;
import io.ekbatan.distributedjobs.DistributedJob;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only periodic report - counts wallets and sums their balances, logs the result. No
 * action invocation, no writes. Demonstrates the "scheduled query" pattern: useful for ops
 * dashboards, periodic metrics exporters, freshness probes.
 *
 * <p>Like every {@link DistributedJob}, this runs at most once per slot across the entire
 * cluster - three worker pods running this app share one scheduled_tasks row, so the report
 * fires once every {@link #schedule() schedule period}, not three times.
 */
@EkbatanDistributedJob
public class WalletReportJob extends DistributedJob {

    private static final Logger LOG = LoggerFactory.getLogger(WalletReportJob.class);

    private final WalletRepository walletRepository;

    public WalletReportJob(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    public String name() {
        return "wallet-report";
    }

    @Override
    public Schedule schedule() {
        return FixedDelay.ofSeconds(5);
    }

    @Override
    public void execute(ExecutionContext ctx) {
        final var wallets = walletRepository.findAll();
        final var total = wallets.stream().map(w -> w.balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        LOG.info("WalletReportJob: {} wallets, total balance = {}", wallets.size(), total);
    }
}
