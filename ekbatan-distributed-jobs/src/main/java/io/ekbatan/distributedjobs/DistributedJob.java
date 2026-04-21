package io.ekbatan.distributedjobs;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;

/**
 * A scheduled task that runs at most once across the cluster per scheduled slot.
 *
 * <p>Coordination is delegated to db-scheduler: every instance polls the shared database, only
 * one wins the atomic claim per scheduled slot, and crash recovery is handled via heartbeat
 * staleness. Implementers provide a unique cluster-wide {@link #name() name}, a
 * {@link #schedule() schedule} (any db-scheduler {@link Schedule} implementation), and the
 * {@link #execute(ExecutionContext) work} to perform.
 */
public abstract class DistributedJob {

    /** Cluster-wide unique identifier for this job; persisted in the {@code scheduled_tasks} table. */
    public abstract String name();

    /** When the job should run next, computed by db-scheduler from the previous execution. */
    public abstract Schedule schedule();

    /**
     * The actual work; runs on a worker thread of the local {@link JobRegistry}. Throwing an
     * exception is treated as a failed execution by db-scheduler — {@code consecutive_failures}
     * is incremented on the task row and the next run is rescheduled per the {@link Schedule}.
     */
    public abstract void execute(ExecutionContext ctx);
}
