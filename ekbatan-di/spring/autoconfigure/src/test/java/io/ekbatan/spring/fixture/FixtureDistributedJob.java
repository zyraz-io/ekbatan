package io.ekbatan.spring.fixture;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.FixedDelay;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import io.ekbatan.di.EkbatanDistributedJob;
import io.ekbatan.distributedjobs.DistributedJob;

/**
 * Test-only DistributedJob used to verify {@code @EkbatanDistributedJob} discovery and the
 * conditional auto-config wiring of {@code JobRegistry}. Not invoked — tests only check that
 * the registry bean exists (or doesn't) and is correctly configured.
 */
@EkbatanDistributedJob
public class FixtureDistributedJob extends DistributedJob {

    @Override
    public String name() {
        return "fixture-distributed-job";
    }

    @Override
    public Schedule schedule() {
        return FixedDelay.ofMinutes(60);
    }

    @Override
    public void execute(ExecutionContext ctx) {
        // no-op for tests
    }
}
