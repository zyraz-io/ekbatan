package io.ekbatan.test.di;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.ekbatan.core.action.ActionExecutor;
import io.ekbatan.test.di.shared.widget.action.WidgetCreateAction;
import io.ekbatan.test.di.shared.widget.handler.WidgetCreatedCounterHandler;
import io.ekbatan.test.di.shared.widget.models.WidgetState;
import io.ekbatan.test.di.shared.widget.repository.WidgetRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end smoke test for the {@code ekbatan-di:spring-boot-starter}.
 *
 * <p>Boots a real {@code @SpringBootApplication} context against a Testcontainers Postgres,
 * letting the auto-config discover {@code @EkbatanAction} {@link WidgetCreateAction},
 * {@code @EkbatanRepository} {@link WidgetRepository}, and {@code @EkbatanEventHandler}
 * {@link WidgetCreatedCounterHandler} (all from the {@code di:shared} module) via classpath
 * scan, then runs the action through {@link ActionExecutor#execute(java.security.Principal,
 * Class, Object)} and asserts both:
 *
 * <ol>
 *   <li>The widget is persisted and can be fetched via the autowired repository.</li>
 *   <li>The event handler is invoked asynchronously after the fan-out and handling jobs run.</li>
 * </ol>
 *
 * <p>This is the only place where the full wiring graph is exercised: classpath scan → Spring DI
 * of repository and handler → {@code AutowireCapableBeanFactory.createBean} for the action's
 * singleton → real {@code DatabaseRegistry} with a Hikari pool → {@code SingleTableJsonEventPersister}
 * writing to {@code eventlog.events} → {@code EventFanoutJob} writing
 * {@code eventlog.event_notifications} rows → {@code EventHandlingJob} draining them and
 * invoking the handler → repository fetching from the real {@code widgets} table.
 *
 * <p>For test simplicity the scheduler's {@code jobsConfig} pool points at the same Postgres
 * as the app data — the {@code scheduled_tasks} table lives alongside {@code widgets} and
 * {@code eventlog.*}. Production deployments should typically use a dedicated scheduler
 * database; that's a separate, more elaborate integration test (deferred).
 */
@SpringBootTest(
        classes = {EkbatanSpringBootTestApp.class, TestcontainersConfiguration.class},
        properties = {
            "ekbatan.namespace=test.spring",
            // Sharding — single shard. Container-dependent jdbcUrl/username/password
            // come from the DynamicPropertyRegistrar in TestcontainersConfiguration.
            "ekbatan.sharding.defaultShard.group=0",
            "ekbatan.sharding.defaultShard.member=0",
            "ekbatan.sharding.groups[0].group=0",
            "ekbatan.sharding.groups[0].name=default",
            "ekbatan.sharding.groups[0].members[0].member=0",
            "ekbatan.sharding.groups[0].members[0].configs.primaryConfig.maximumPoolSize=5",
            "ekbatan.sharding.groups[0].members[0].configs.jobsConfig.maximumPoolSize=4",
            // Tighten poll intervals so the test doesn't sit idle for default waits.
            "ekbatan.local-event-handler.fanoutPollDelay=200ms",
            "ekbatan.local-event-handler.handlingPollDelay=200ms",
            "ekbatan.jobs.pollingInterval=1s",
            "ekbatan.jobs.shutdownMaxWait=5s",
            // Opt in to the in-process handling job (off by default).
            // Read by @ConditionalOnProperty during bean definition phase, which is
            // BEFORE DynamicPropertyRegistrar runs — must be set here, not the registrar.
            "ekbatan.local-event-handler.handling.enabled=true",
        })
class EkbatanSpringBootStarterIntegrationTest {

    @Autowired
    ActionExecutor executor;

    @Autowired
    WidgetRepository widgetRepository;

    @Autowired
    WidgetCreatedCounterHandler counterHandler;

    @Test
    void createAction_persists_widget_and_repository_can_fetch_it() throws Exception {
        var created = executor.execute(
                () -> "test-user", WidgetCreateAction.class, new WidgetCreateAction.Params("first widget", "blue"));

        var fetched = widgetRepository.findById(created.id.getValue());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().name).isEqualTo("first widget");
        assertThat(fetched.get().color).isEqualTo("blue");
        assertThat(fetched.get().state).isEqualTo(WidgetState.ACTIVE);
        assertThat(fetched.get().version).isEqualTo(1L);
    }

    @Test
    void event_handler_is_invoked_after_widget_is_created_via_action() throws Exception {
        var beforeCount = counterHandler.callCount();

        var created = executor.execute(
                () -> "test-user", WidgetCreateAction.class, new WidgetCreateAction.Params("handled widget", "red"));

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(counterHandler.callCount()).isEqualTo(beforeCount + 1);
                    assertThat(counterHandler.handledNames()).contains("handled widget");
                });

        var fetched = widgetRepository.findById(created.id.getValue());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().name).isEqualTo("handled widget");
    }
}
