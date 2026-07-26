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
 * {@code heartbeatInterval x missedHeartbeatsLimit} - 60 seconds on this framework's defaults -
 * and runs it again, while the first may still be part-way through {@code execute()}. The claim protects the row, not your method: the database stops the stale
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

    /**
     * No-arg constructor for subclasses.
     *
     * <p>You create the instance - by hand, or through your DI container - and hand it to
     * {@link JobRegistry.Builder#withJob}. The builder registers instances; it never constructs
     * them, so anything a job needs must be injected before it is registered.
     */
    protected DistributedJob() {}

    /**
     * {@return cluster-wide unique identifier for this job}
     *
     * <p>This is not a label. It is the primary key of the job's row in {@code scheduled_tasks},
     * and it is how every instance in the cluster recognises a job as the same job - which is what
     * stops five servers from each running the daily report.
     *
     * <p>It must therefore be <strong>non-blank</strong> and <strong>stable</strong>: the same
     * value on every call, on every instance, and across restarts. A name derived from
     * {@code UUID.randomUUID()}, from the current date, or from a field the DI container has not
     * populated yet breaks that - each boot writes a fresh row, the previous one is orphaned, and
     * the job never actually recurs.
     *
     * <p>{@code JobRegistry} asks for the name once and rejects a blank one at build time. It
     * cannot check stability across restarts, so that part is on the implementation.
     */
    public abstract String name();

    /** {@return when the job should run next; computed by db-scheduler from the previous execution} */
    public abstract Schedule schedule();

    /**
     * The actual work; runs on a worker thread of the local {@link JobRegistry}. Throwing an
     * exception is treated as a failed execution by db-scheduler - {@code consecutive_failures}
     * is incremented on the task row and the next run is rescheduled per the {@link Schedule}.
     *
     * <p><strong>The same slot can run twice.</strong> After 60 seconds of failed heartbeats
     * another instance reclaims the row and runs the job again, and the instance being replaced is
     * neither told nor interrupted. Heartbeats are written by a housekeeper thread, so work that
     * does not touch the scheduler's database keeps going without noticing - the usual cause is not
     * a crash but a brief inability to reach the database. Write this method so a repeat is
     * harmless.
     *
     * <p>Where that is impractical, {@code ctx} carries the instance's own standing and the job can
     * stop before the takeover:
     *
     * {@snippet :
     * var health = ctx.getCurrentlyExecuting().getHeartbeatState();
     * for (var item : items) {
     *     // 0.0 just after a successful heartbeat, 1.0 when another instance may claim the row.
     *     if (health.getFractionDead() > 0.5) {
     *         return;
     *     }
     *     process(item);
     * }
     * }
     *
     * <p>Returning early counts as a normal completion, so the row reschedules per its
     * {@link Schedule}. The check is cooperative and only as good as where it is placed: it fits a
     * job that loops over work items and cannot help one inside a single long call, and it narrows
     * the overlap window rather than closing it.
     *
     * @param ctx the db-scheduler execution context carrying timing, retry metadata, and - via
     *     {@code getCurrentlyExecuting().getHeartbeatState()} - this execution's heartbeat standing.
     */
    public abstract void execute(ExecutionContext ctx);
}
