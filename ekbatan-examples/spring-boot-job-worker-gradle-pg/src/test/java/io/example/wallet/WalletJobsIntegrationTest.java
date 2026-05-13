package io.example.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.ekbatan.core.action.ActionExecutor;
import io.example.wallet.action.WalletCreateAction;
import io.example.wallet.model.NotificationKind;
import io.example.wallet.repository.NotificationRepository;
import io.example.wallet.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * End-to-end integration test for the job-worker. No HTTP — wallets are seeded directly via the
 * injected {@link ActionExecutor}; the {@code WalletStipendJob} then runs against them on its
 * own schedule and we use Awaitility to wait for the side effects to land.
 *
 * <p>Validates two things:
 *
 * <ol>
 *   <li>The job actually fires and updates wallet balances via {@code WalletDepositMoneyAction}.</li>
 *   <li>The listen-to-yourself chain still works under job-driven execution — every stipend
 *       deposit triggers {@code WalletMoneyDepositedEventHandler} → {@code CreateNotificationAction},
 *       so a {@code notifications} row appears for each deposit.</li>
 * </ol>
 */
@SpringBootTest(
        classes = {Application.class, TestcontainersConfiguration.class},
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "ekbatan.namespace=test.wallet.worker",
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=default",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4",
            // Tighten poll intervals — both the framework's local-event-handler jobs and our
            // own @EkbatanDistributedJobs use them. Defaults are tuned for production (longer
            // intervals to keep DB load low); tests want everything to converge quickly.
            "ekbatan.local-event-handler.fanoutPollDelay=200ms",
            "ekbatan.local-event-handler.handlingPollDelay=200ms",
            "ekbatan.jobs.pollingInterval=200ms",
            "ekbatan.jobs.shutdownMaxWait=5s",
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class WalletJobsIntegrationTest {

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void stipend_job_tops_up_underfunded_wallets_and_triggers_notifications() throws Exception {
        // GIVEN — three wallets with balance 0 (well below the 100.00 stipend threshold)
        for (int i = 0; i < 3; i++) {
            executor.execute(
                    () -> "test-user",
                    WalletCreateAction.class,
                    new WalletCreateAction.Params(UUID.randomUUID(), Currency.getInstance("USD"), BigDecimal.ZERO));
        }

        // WHEN / THEN — the stipend job fires every 2s; within ~10s every wallet should have
        // received at least one 10.00 deposit. The deposit emits an event that the local-event-
        // handler picks up; the handler creates a Notification. We assert both: the balances
        // got bumped AND a corresponding notifications row landed for each wallet.
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    final var wallets = walletRepository.findAll();
                    assertThat(wallets).hasSize(3);
                    assertThat(wallets)
                            .allSatisfy(w -> assertThat(w.balance).isGreaterThanOrEqualTo(new BigDecimal("10.00")));

                    final var allNotifications = wallets.stream()
                            .flatMap(w -> notificationRepository.findAllByWalletId(w.id.getValue()).stream())
                            .toList();
                    assertThat(allNotifications).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(allNotifications)
                            .allSatisfy(n -> assertThat(n.kind).isEqualTo(NotificationKind.MONEY_DEPOSITED));
                    assertThat(allNotifications)
                            .allSatisfy(n -> assertThat(n.recipient).isEqualTo("stipend@system.local"));
                });
    }
}
