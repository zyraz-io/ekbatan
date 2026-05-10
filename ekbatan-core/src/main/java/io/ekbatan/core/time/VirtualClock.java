package io.ekbatan.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A {@link Clock} that supports pause, advance, and time-skew operations — designed for
 * tests that need to drive time-dependent behavior deterministically. Pass an instance to
 * {@link io.ekbatan.core.action.ActionExecutor.Builder#clock(Clock)} in test setup, then
 * manipulate via {@link #pause()} / {@link #advance(Duration)} / {@link #resumeFrom(Instant)}
 * to step through time-sensitive action behavior.
 *
 * <p>Three modes:
 * <ul>
 *   <li><b>Real time</b> (default): delegates to the underlying real clock.</li>
 *   <li><b>Frozen</b> (after {@link #pause()} / {@link #pauseAt(Instant)}): {@link #instant()}
 *       returns the same instant until {@link #advance(Duration)} or a resume call.</li>
 *   <li><b>Skewed</b> (after {@link #resumeFrom(Instant)}): time progresses at real-clock
 *       rate but anchored to a different instant than wall-clock-now.</li>
 * </ul>
 */
public class VirtualClock extends Clock {

    private final Clock realClock;
    private Instant frozenInstant;
    private Instant virtualAnchor;
    private Instant realAnchor;

    /** Constructs a virtual clock backed by {@link Clock#systemUTC()}. */
    public VirtualClock() {
        this(Clock.systemUTC());
    }

    /**
     * Constructs a virtual clock backed by the system clock in the given zone.
     *
     * @param zone the zone for the underlying real clock.
     */
    public VirtualClock(ZoneId zone) {
        this(Clock.system(zone));
    }

    /**
     * Constructs a virtual clock backed by an arbitrary real clock.
     *
     * @param realClock the underlying real clock; used while not paused or skewed.
     */
    public VirtualClock(Clock realClock) {
        this.realClock = realClock;
    }

    @Override
    public Instant instant() {
        if (frozenInstant != null) {
            return frozenInstant;
        }
        if (virtualAnchor != null) {
            return virtualAnchor.plus(Duration.between(realAnchor, realClock.instant()));
        }
        return realClock.instant();
    }

    @Override
    public ZoneId getZone() {
        return realClock.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        var clock = new VirtualClock(realClock.withZone(zone));
        clock.frozenInstant = this.frozenInstant;
        clock.virtualAnchor = this.virtualAnchor;
        clock.realAnchor = this.realAnchor;
        return clock;
    }

    /** Freezes the clock at the current instant. */
    public void pause() {
        this.frozenInstant = instant();
        this.virtualAnchor = null;
        this.realAnchor = null;
    }

    /**
     * Freezes the clock at a specific instant.
     *
     * @param instant the instant to freeze at.
     */
    public void pauseAt(Instant instant) {
        this.frozenInstant = instant;
        this.virtualAnchor = null;
        this.realAnchor = null;
    }

    /**
     * Advances the (paused) clock by a duration.
     *
     * @param duration the amount to advance by.
     * @throws IllegalStateException if the clock is not currently paused.
     */
    public void advance(Duration duration) {
        if (frozenInstant == null) {
            throw new IllegalStateException("Cannot advance: clock is not paused");
        }
        this.frozenInstant = frozenInstant.plus(duration);
    }

    /**
     * Resumes the clock anchored to an arbitrary instant — time progresses at real-clock rate
     * but reports a skewed instant.
     *
     * @param instant the virtual now-instant; subsequent reads progress from here.
     */
    public void resumeFrom(Instant instant) {
        this.frozenInstant = null;
        this.virtualAnchor = instant;
        this.realAnchor = realClock.instant();
    }

    /** Resumes the clock at real wall-clock time, discarding any pause or skew. */
    public void resume() {
        this.frozenInstant = null;
        this.virtualAnchor = null;
        this.realAnchor = null;
    }
}
