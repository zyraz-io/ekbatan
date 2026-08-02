package io.ekbatan.distributedjobs;

import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;

/**
 * A scheduled task that runs on a single instance per scheduled slot.
 *
 * <p>Coordination is delegated to db-scheduler: every instance polls the shared database and only
 * one wins the atomic claim for a slot. That holds in normal operation - but it is not a
 * guarantee. If an instance stops writing heartbeats, whether because it died or because its JVM
 * stalled or lost the database, another instance treats the execution as dead after
 * {@code heartbeatInterval x 2} and runs it again - while the first may still be part-way through
 * {@code execute()}. The claim protects the row, not your method: the database stops the stale
 * instance from recording a result, but it cannot undo side effects it has already caused.
 *
 * <p><strong>{@link #execute(ExecutionContext)} must therefore be idempotent</strong> - running
 * the same slot twice must be harmless. Charge a card, send an email or post to an external API
 * only behind a check that makes a repeat a no-op.
 *
 * <p>Implementers provide a unique cluster-wide {@link #name() name}, a {@link #schedule()
 * schedule} (any db-scheduler {@link Schedule} implementation), and the
 * {@link #execute(ExecutionContext) work} to perform.
 */
public abstract class DistributedJob {

    /** No-arg constructor for subclasses; instantiated by your DI framework or by the JobRegistry builder. */
    protected DistributedJob() {}

    /** {@return cluster-wide unique identifier for this job; persisted in the {@code scheduled_tasks} table} */
    public abstract String name();

    /** {@return when the job should run next; computed by db-scheduler from the previous execution} */
    public abstract Schedule schedule();

    /**
     * The actual work; runs on a worker thread of the local {@link JobRegistry}. Throwing an
     * exception is treated as a failed execution by db-scheduler - {@code consecutive_failures}
     * is incremented on the task row and the next run is rescheduled per the {@link Schedule}.
     *
     * @param ctx the db-scheduler execution context carrying timing + retry metadata.
     */
    public abstract void execute(ExecutionContext ctx);
}
